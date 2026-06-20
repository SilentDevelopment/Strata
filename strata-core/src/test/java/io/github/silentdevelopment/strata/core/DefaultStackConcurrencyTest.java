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
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

final class DefaultStackConcurrencyTest {

    private static final Type<Integer> INTEGER = Type.of("integer", Integer.class);
    private static final Namespace NAMESPACE = Namespace.of("concurrency_test");
    private static final Codec<Integer> CODEC = new Codec<>() {
        @Override
        public Encoded encode(Integer value) {
            return Encoded.of(String.valueOf(value).getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public Integer decode(Encoded encoded, Type<Integer> type) {
            return Integer.parseInt(new String(encoded.bytes(), StandardCharsets.UTF_8));
        }
    };

    @Test
    void concurrentMutateRetriesConflictsAndPreservesBothUpdates() throws Exception {
        AtomicLayer durable = new AtomicLayer("durable", Role.DURABLE);
        Stack stack = Strata.stack().durable(durable).codec(INTEGER, CODEC).build();
        Key<Integer> key = NAMESPACE.key(INTEGER, "values", "counter");

        Result<Void> save = stack.save(key, 0).join();

        assertThat(save.successful()).isTrue();

        CountDownLatch bothMutatorsStarted = new CountDownLatch(2);
        CountDownLatch releaseBothMutators = new CountDownLatch(1);
        AtomicInteger mutationCalls = new AtomicInteger();

        CompletableFuture<Result<Entry<Integer>>> left = stack.mutate(key, current -> mutateWithInitialBarrier(current, mutationCalls, bothMutatorsStarted, releaseBothMutators));
        CompletableFuture<Result<Entry<Integer>>> right = stack.mutate(key, current -> mutateWithInitialBarrier(current, mutationCalls, bothMutatorsStarted, releaseBothMutators));

        assertThat(bothMutatorsStarted.await(5, TimeUnit.SECONDS)).isTrue();

        releaseBothMutators.countDown();

        Result<Entry<Integer>> leftResult = left.join();
        Result<Entry<Integer>> rightResult = right.join();

        assertThat(leftResult.successful()).isTrue();
        assertThat(rightResult.successful()).isTrue();

        Result<Entry<Integer>> finalValue = stack.load(key).join();

        assertThat(finalValue.successful()).isTrue();
        assertThat(finalValue.valueOrThrow().value()).isEqualTo(2);
    }

    private static Integer mutateWithInitialBarrier(Integer current, AtomicInteger mutationCalls, CountDownLatch bothMutatorsStarted, CountDownLatch releaseBothMutators) {
        int call = mutationCalls.incrementAndGet();

        if (call <= 2) {
            bothMutatorsStarted.countDown();

            try {
                if (!releaseBothMutators.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting to release concurrent mutators.");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting to release concurrent mutators.", exception);
            }
        }

        return current + 1;
    }

    private static final class AtomicLayer implements Layer {

        private final String name;
        private final Role role;
        private final Map<String, Envelope> values = new ConcurrentHashMap<>();

        private AtomicLayer(String name, Role role) {
            this.name = Objects.requireNonNull(name, "name");
            this.role = Objects.requireNonNull(role, "role");
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
            Envelope envelope = values.get(key.external());

            if (envelope == null || envelope.metadata().expired()) {
                values.remove(key.external());
                return CompletableFuture.completedFuture(Result.notFound(List.of(new LayerReport(name, role, Status.NOT_FOUND, Duration.ZERO, List.of()))));
            }

            return CompletableFuture.completedFuture(Result.success(envelope, List.of(LayerReport.success(this, Duration.ZERO))));
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
            synchronized (this) {
                SaveCondition condition = context.saveOptions().condition();

                if (condition.expectedStamp() != null) {
                    Envelope current = values.get(envelope.key().external());

                    if (current == null || !current.stamp().equals(condition.expectedStamp())) {
                        Failure failure = new Failure(name, role, "Stamp conflict.", null);
                        return CompletableFuture.completedFuture(Result.conflict(List.of(failure), List.of(new LayerReport(name, role, Status.CONFLICT, Duration.ZERO, List.of(failure)))));
                    }
                }

                values.put(envelope.key().external(), envelope);
                return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.ZERO))));
            }
        }

        @Override
        public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
            values.remove(key.external());
            return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.ZERO))));
        }

        @Override
        public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
            List<Envelope> result = values.values().stream()
                    .filter(envelope -> envelope.key().type().id().equals(type.id()))
                    .filter(envelope -> !envelope.metadata().expired())
                    .filter(query::matches)
                    .toList();

            return CompletableFuture.completedFuture(Result.success(result, List.of(LayerReport.success(this, Duration.ZERO))));
        }

    }

}