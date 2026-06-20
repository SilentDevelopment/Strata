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
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.ReadPolicy;
import io.github.silentdevelopment.strata.operation.ReadRepair;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

final class DefaultStackCompensationTest {

    private static final Type<String> STRING = Type.of("string", String.class);
    private static final Namespace NAMESPACE = Namespace.of("compensation_test");
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
    void durableRequiredSaveReturnsWarningsWhenSoftAndBufferFail() {
        TestLayer soft = TestLayer.failing("soft", Role.SOFT, true, false, false);
        TestLayer buffer = TestLayer.failing("buffer", Role.BUFFER, true, false, false);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("warning-save");

        Result<Void> result = stack.save(key, "value").join();

        assertThat(result.status()).isEqualTo(Status.SUCCESS_WITH_WARNINGS);
        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.failures()).hasSize(2);
        assertThat(durable.loadDirect(key).successful()).isTrue();
        assertThat(value(durable.loadDirect(key).valueOrThrow())).isEqualTo("value");
    }

    @Test
    void durableSaveFailureFailsAndDoesNotWriteSoftOrBuffer() {
        TestLayer soft = TestLayer.working("soft", Role.SOFT);
        TestLayer buffer = TestLayer.working("buffer", Role.BUFFER);
        TestLayer durable = TestLayer.failing("durable", Role.DURABLE, true, false, false);
        Stack stack = Strata.stack().soft(soft).buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("durable-failure");

        Result<Void> result = stack.save(key, "value").join();

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(soft.loadDirect(key).status()).isEqualTo(Status.NOT_FOUND);
        assertThat(buffer.loadDirect(key).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void readRepairFailureReturnsValueWithWarningsByDefault() {
        TestLayer buffer = TestLayer.failing("buffer", Role.BUFFER, true, false, false);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("repair-warning");

        durable.saveDirect(envelope(key, "durable-value", Map.of()));

        Result<Entry<String>> result = stack.load(key).join();

        assertThat(result.status()).isEqualTo(Status.SUCCESS_WITH_WARNINGS);
        assertThat(result.valueOrThrow().value()).isEqualTo("durable-value");
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void readRepairRequiredFailsWhenRepairFails() {
        TestLayer buffer = TestLayer.failing("buffer", Role.BUFFER, true, false, false);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("repair-required");

        durable.saveDirect(envelope(key, "durable-value", Map.of()));

        Result<Entry<String>> result = stack.load(key, new LoadOptions(ReadPolicy.DURABLE_WITH_REPAIR, ReadRepair.REQUIRED)).join();

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.failures()).hasSize(1);
    }

    @Test
    void deleteDurableInvalidateReturnsWarningsWhenBufferDeleteFails() {
        TestLayer buffer = TestLayer.failing("buffer", Role.BUFFER, false, false, true);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("delete-warning");

        Result<Void> save = stack.save(key, "value").join();

        assertThat(save.successful()).isTrue();
        assertThat(durable.loadDirect(key).successful()).isTrue();

        Result<Void> delete = stack.delete(key).join();

        assertThat(delete.status()).isEqualTo(Status.SUCCESS_WITH_WARNINGS);
        assertThat(delete.failures()).hasSize(1);
        assertThat(durable.loadDirect(key).status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void defaultReadUsesDurableOverStaleSoftAndBuffer() {
        TestLayer soft = TestLayer.working("soft", Role.SOFT);
        TestLayer buffer = TestLayer.working("buffer", Role.BUFFER);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).buffer(buffer).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("stale-read");

        soft.saveDirect(envelope(key, "soft-stale", Map.of()));
        buffer.saveDirect(envelope(key, "buffer-stale", Map.of()));
        durable.saveDirect(envelope(key, "durable-current", Map.of()));

        Result<Entry<String>> firstAvailableBeforeRepair = stack.load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstAvailableBeforeRepair.successful()).isTrue();
        assertThat(firstAvailableBeforeRepair.valueOrThrow().value()).isEqualTo("soft-stale");

        Result<Entry<String>> defaultLoad = stack.load(key).join();

        assertThat(defaultLoad.successful()).isTrue();
        assertThat(defaultLoad.valueOrThrow().value()).isEqualTo("durable-current");

        Result<Entry<String>> firstAvailableAfterRepair = stack.load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstAvailableAfterRepair.successful()).isTrue();
        assertThat(firstAvailableAfterRepair.valueOrThrow().value()).isEqualTo("durable-current");
    }

    @Test
    void durableOnlyDeleteLeavesSoftValueForFirstAvailableReads() {
        TestLayer soft = TestLayer.working("soft", Role.SOFT);
        TestLayer durable = TestLayer.working("durable", Role.DURABLE);
        Stack stack = Strata.stack().soft(soft).durable(durable).codec(STRING, CODEC).build();
        Key<String> key = key("durable-only-delete");

        Result<Void> save = stack.save(key, "value").join();

        assertThat(save.successful()).isTrue();

        Result<Void> delete = stack.delete(key, new DeleteOptions(io.github.silentdevelopment.strata.operation.DeletePolicy.DURABLE_ONLY)).join();

        assertThat(delete.successful()).isTrue();
        assertThat(durable.loadDirect(key).status()).isEqualTo(Status.NOT_FOUND);
        assertThat(soft.loadDirect(key).successful()).isTrue();

        Result<Entry<String>> defaultLoad = stack.load(key).join();
        Result<Entry<String>> firstAvailable = stack.load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(defaultLoad.status()).isEqualTo(Status.NOT_FOUND);
        assertThat(firstAvailable.successful()).isTrue();
        assertThat(firstAvailable.valueOrThrow().value()).isEqualTo("value");
    }

    private static Key<String> key(String id) {
        return NAMESPACE.key(STRING, "values", id);
    }

    private static Envelope envelope(Key<String> key, String value, Map<String, String> indexes) {
        Encoded encoded = CODEC.encode(value);
        Metadata metadata = Metadata.now().withIndexes(indexes);
        return new Envelope(key, encoded, Stamp.random(), metadata, false);
    }

    private static String value(Envelope envelope) {
        return CODEC.decode(envelope.encoded(), STRING);
    }

    private static final class TestLayer implements Layer {

        private final String name;
        private final Role role;
        private final boolean failSave;
        private final boolean failLoad;
        private final boolean failDelete;
        private final Map<String, Envelope> entries = new ConcurrentHashMap<>();

        private TestLayer(String name, Role role, boolean failSave, boolean failLoad, boolean failDelete) {
            this.name = Objects.requireNonNull(name, "name");
            this.role = Objects.requireNonNull(role, "role");
            this.failSave = failSave;
            this.failLoad = failLoad;
            this.failDelete = failDelete;
        }

        private static TestLayer working(String name, Role role) {
            return new TestLayer(name, role, false, false, false);
        }

        private static TestLayer failing(String name, Role role, boolean failSave, boolean failLoad, boolean failDelete) {
            return new TestLayer(name, role, failSave, failLoad, failDelete);
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
            if (failLoad) {
                return CompletableFuture.completedFuture(failedEnvelope("load failed"));
            }

            return CompletableFuture.completedFuture(loadDirect(key));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
            if (failSave) {
                return CompletableFuture.completedFuture(failedVoid("save failed"));
            }

            SaveCondition condition = context.saveOptions().condition();

            if (condition.expectedStamp() != null) {
                Envelope current = entries.get(envelope.key().external());

                if (current != null && !current.stamp().equals(condition.expectedStamp())) {
                    Failure failure = new Failure(name, role, "Stamp conflict.", null);
                    return CompletableFuture.completedFuture(Result.conflict(List.of(failure), List.of(new LayerReport(name, role, Status.CONFLICT, Duration.ZERO, List.of(failure)))));
                }
            }

            entries.put(envelope.key().external(), envelope);
            return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.ZERO))));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
            if (failDelete) {
                return CompletableFuture.completedFuture(failedVoid("delete failed"));
            }

            entries.remove(key.external());
            return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.ZERO))));
        }

        @Override
        public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
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

            return CompletableFuture.completedFuture(Result.success(result, List.of(LayerReport.success(this, Duration.ZERO))));
        }

        private Result<Envelope> loadDirect(Key<?> key) {
            Envelope envelope = entries.get(key.external());

            if (envelope == null || envelope.metadata().expired()) {
                entries.remove(key.external());
                return Result.notFound(List.of(new LayerReport(name, role, Status.NOT_FOUND, Duration.ZERO, List.of())));
            }

            return Result.success(envelope, List.of(LayerReport.success(this, Duration.ZERO)));
        }

        private void saveDirect(Envelope envelope) {
            entries.put(envelope.key().external(), envelope);
        }

        private Result<Void> failedVoid(String message) {
            Failure failure = new Failure(name, role, message, null);
            return Result.failure(List.of(failure), List.of(new LayerReport(name, role, Status.FAILED, Duration.ZERO, List.of(failure))));
        }

        private Result<Envelope> failedEnvelope(String message) {
            Failure failure = new Failure(name, role, message, null);
            return Result.failure(List.of(failure), List.of(new LayerReport(name, role, Status.FAILED, Duration.ZERO, List.of(failure))));
        }

    }

}