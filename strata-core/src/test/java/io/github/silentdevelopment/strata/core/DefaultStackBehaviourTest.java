package io.github.silentdevelopment.strata.core;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Strata;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.DeletePolicy;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.ReadPolicy;
import io.github.silentdevelopment.strata.operation.ReadRepair;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

final class DefaultStackBehaviourTest {

    private static final Type<String> STRING = Type.of("string", String.class);
    private static final Namespace NAMESPACE = Namespace.of("core_test");
    private static final Codec<String> CODEC = new Codec<>() {
        @Override
        public Encoded encode(String value) {
            return Encoded.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public String decode(Encoded encoded, Type<String> type) {
            return new String(encoded.bytes(), StandardCharsets.UTF_8);
        }
    };

    @Test
    void durableReadRepairsSoftLayerByDefault() {
        TestLayer soft = new TestLayer("soft", Role.SOFT);
        TestLayer durable = new TestLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("repair");

        stack.save(key, "value").join();
        soft.clear();

        Result<Entry<String>> loaded = stack.load(key).join();

        assertThat(loaded.successful()).isTrue();
        assertThat(loaded.valueOrThrow().value()).isEqualTo("value");
        assertThat(soft.loadDirect(key).successful()).isTrue();
    }

    @Test
    void durableFirstWriteSucceedsWithWarningsWhenSoftLayerFails() {
        Layer soft = new SaveFailingLayer("soft-failure", Role.SOFT);
        TestLayer durable = new TestLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("partial-save");

        Result<Void> saved = stack.save(key, "value").join();

        assertThat(saved.status()).isEqualTo(Status.SUCCESS_WITH_WARNINGS);
        assertThat(saved.hasWarnings()).isTrue();
        assertThat(durable.loadDirect(key).successful()).isTrue();
    }

    @Test
    void matchingStampRejectsStaleSave() {
        TestLayer durable = new TestLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("conflict");

        stack.save(key, "one").join();
        Stamp oldStamp = stack.load(key).join().valueOrThrow().stamp();

        Result<Void> secondSave = stack.save(key, "two", SaveOptions.matching(oldStamp)).join();
        Result<Void> staleSave = stack.save(key, "three", SaveOptions.matching(oldStamp)).join();

        assertThat(secondSave.successful()).isTrue();
        assertThat(staleSave.status()).isEqualTo(Status.CONFLICT);
        assertThat(stack.load(key).join().valueOrThrow().value()).isEqualTo("two");
    }

    @Test
    void mutatePreservesIndexes() {
        TestLayer durable = new TestLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("indexed");

        stack.save(key, "one", SaveOptions.defaults().withIndex("group", "a")).join();
        Result<Entry<String>> mutated = stack.mutate(key, value -> value + "-changed").join();
        Result<List<Entry<String>>> queried = stack.findByIndex(STRING, "group", "a").join();

        assertThat(mutated.successful()).isTrue();
        assertThat(mutated.valueOrThrow().value()).isEqualTo("one-changed");
        assertThat(queried.successful()).isTrue();
        assertThat(queried.valueOrThrow()).hasSize(1);
        assertThat(queried.valueOrThrow().getFirst().value()).isEqualTo("one-changed");
    }

    @Test
    void durableOnlyDeleteCanLeaveSoftValueForFirstAvailableReads() {
        TestLayer soft = new TestLayer("soft", Role.SOFT);
        TestLayer durable = new TestLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("durable-only-delete");

        stack.save(key, "value").join();
        stack.delete(key, new DeleteOptions(DeletePolicy.DURABLE_ONLY)).join();

        Result<Entry<String>> durableLoad = stack.load(key).join();
        Result<Entry<String>> firstAvailable = stack.load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(durableLoad.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(firstAvailable.successful()).isTrue();
        assertThat(firstAvailable.valueOrThrow().value()).isEqualTo("value");
    }

    private static Key<String> key(String id) {
        return NAMESPACE.key(STRING, "values", id);
    }

    private static final class TestLayer implements Layer {

        private final String name;
        private final Role role;
        private final Map<String, Envelope> entries = new ConcurrentHashMap<>();

        private TestLayer(String name, Role role) {
            this.name = name;
            this.role = role;
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull Role role() {
            return role;
        }

        @Override
        public @NotNull Capabilities capabilities() {
            return Capabilities.keyValue(role).withQuery(true).withIndex(true).withTtl(true);
        }

        @Override
        public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
            return CompletableFuture.completedFuture(loadDirect(key));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
            Objects.requireNonNull(envelope, "envelope");
            Instant start = Instant.now();
            SaveCondition condition = context.saveOptions().condition();
            if (condition.expectedStamp() != null) {
                Envelope current = entries.get(envelope.key().external());
                if (current != null && !current.stamp().equals(condition.expectedStamp())) {
                    Failure failure = new Failure(name, role, "Stamp conflict.", null);
                    return CompletableFuture.completedFuture(Result.conflict(List.of(failure), List.of(new LayerReport(name, role, Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure)))));
                }
            }
            entries.put(envelope.key().external(), envelope);
            return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
            Instant start = Instant.now();
            entries.remove(key.external());
            return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
        }

        @Override
        public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
            Instant start = Instant.now();
            List<Envelope> result = new ArrayList<>();
            for (Envelope envelope : entries.values()) {
                if (!envelope.key().type().equals(type)) {
                    continue;
                }
                if (envelope.metadata().expired()) {
                    continue;
                }
                if (!query.matches(envelope)) {
                    continue;
                }
                result.add(envelope);
            }
            return CompletableFuture.completedFuture(Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
        }

        private Result<Envelope> loadDirect(Key<?> key) {
            Instant start = Instant.now();
            Envelope envelope = entries.get(key.external());
            if (envelope == null || envelope.metadata().expired()) {
                return Result.notFound(List.of(new LayerReport(name, role, Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }
            return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        }

        private void clear() {
            entries.clear();
        }

    }

    private static final class SaveFailingLayer implements Layer {

        private final String name;
        private final Role role;

        private SaveFailingLayer(String name, Role role) {
            this.name = name;
            this.role = role;
        }

        @Override
        public @NotNull String name() {
            return name;
        }

        @Override
        public @NotNull Role role() {
            return role;
        }

        @Override
        public @NotNull Capabilities capabilities() {
            return Capabilities.keyValue(role);
        }

        @Override
        public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
            return CompletableFuture.completedFuture(Result.notFound());
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
            Failure failure = new Failure(name, role, "save failed", null);
            return CompletableFuture.completedFuture(Result.failure(List.of(failure), List.of(new LayerReport(name, role, Status.FAILED, Duration.ZERO, List.of(failure)))));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
            return CompletableFuture.completedFuture(Result.success(null));
        }

    }

}