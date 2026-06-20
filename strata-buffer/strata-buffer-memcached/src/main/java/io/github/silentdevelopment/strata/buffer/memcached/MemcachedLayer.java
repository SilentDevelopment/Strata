package io.github.silentdevelopment.strata.buffer.memcached;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.entry.DefaultEnvelopeCodec;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import net.spy.memcached.CASResponse;
import net.spy.memcached.CASValue;
import net.spy.memcached.MemcachedClient;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Memcached BUFFER layer with key-value, TTL, and CAS conflict support.
 *
 * <p>Query and index operations are unsupported because Memcached has no safe enumeration primitive.</p>
 */
public final class MemcachedLayer implements Layer {

    private final String name;
    private final MemcachedClient client;
    private final String prefix;

    private MemcachedLayer(@NotNull String name, @NotNull MemcachedClient client, @NotNull String prefix) {
        this.name = Objects.requireNonNull(name, "name");
        this.client = Objects.requireNonNull(client, "client");
        this.prefix = normalizePrefix(prefix);
    }

    public static @NotNull MemcachedLayer create(@NotNull MemcachedClient client) {
        return named("memcached", client, "strata:memcached");
    }

    public static @NotNull MemcachedLayer named(@NotNull String name, @NotNull MemcachedClient client, @NotNull String prefix) {
        return new MemcachedLayer(name, client, prefix);
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Role role() {
        return Role.BUFFER;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.keyValue(Role.BUFFER).withTtl(true);
    }

    @Override
    public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> loadNow(key), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> saveNow(envelope, context), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> deleteNow(key), context.executor());
    }

    private Result<Envelope> loadNow(Key<?> key) {
        Instant start = Instant.now();
        try {
            Object value = client.get(memcachedKey(key.external()));
            if (value == null) {
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }
            if (!(value instanceof byte[] bytes)) {
                Failure failure = new Failure(name, role(), "Unexpected Memcached value type: " + value.getClass().getName(), null);
                return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
            }

            Envelope envelope = deserialize(bytes);
            if (envelope.metadata().expired()) {
                client.delete(memcachedKey(key.external())).get();
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }

            return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();
        try {
            SaveCondition condition = context.saveOptions().condition();
            byte[] bytes = serialize(envelope);
            String key = memcachedKey(envelope.key().external());
            int expiration = expirationSeconds(context.saveOptions().ttl());

            if (condition.expectedStamp() != null) {
                CASValue<Object> current = client.gets(key);
                if (current == null) {
                    Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }
                if (!(current.getValue() instanceof byte[] currentBytes)) {
                    Failure failure = new Failure(name, role(), "Unexpected Memcached value type: " + current.getValue().getClass().getName(), null);
                    return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
                }

                Envelope currentEnvelope = deserialize(currentBytes);
                if (!currentEnvelope.stamp().equals(condition.expectedStamp())) {
                    Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }

                CASResponse response = client.cas(key, current.getCas(), expiration, bytes);
                if (response != CASResponse.OK) {
                    Failure failure = new Failure(name, role(), "Memcached CAS failed: " + response, null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }

                return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
            }

            boolean stored = client.set(key, expiration, bytes).get();
            if (!stored) {
                Failure failure = new Failure(name, role(), "Memcached set operation returned false.", null);
                return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();
        try {
            client.delete(memcachedKey(key.external())).get();
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private String memcachedKey(String external) {
        return prefix + external;
    }

    private static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.endsWith(":")) {
            return prefix;
        }
        return prefix + ":";
    }

    private static int expirationSeconds(Duration ttl) {
        if (ttl == null) {
            return 0;
        }
        long seconds = Math.max(1L, ttl.toSeconds());
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, seconds));
    }

    private static byte[] serialize(Envelope envelope) {
        return DefaultEnvelopeCodec.INSTANCE.encode(envelope);
    }

    private static Envelope deserialize(byte[] bytes) {
        return DefaultEnvelopeCodec.INSTANCE.decode(bytes);
    }

}