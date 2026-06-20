package io.github.silentdevelopment.strata.buffer.redis.redisson;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.entry.DefaultEnvelopeCodec;
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
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-backed Redis BUFFER layer using Strata-maintained registry and index sets.
 *
 * <p>Query support is based on maintained sets and does not use Redis KEYS.</p>
 */
public final class RedissonLayer implements Layer {

    private final String name;
    private final RedissonClient client;
    private final RMapCache<String, byte[]> entries;
    private final String prefix;

    private RedissonLayer(@NotNull String name, @NotNull RedissonClient client, @NotNull RMapCache<String, byte[]> entries, @NotNull String prefix) {
        this.name = Objects.requireNonNull(name, "name");
        this.client = Objects.requireNonNull(client, "client");
        this.entries = Objects.requireNonNull(entries, "entries");
        this.prefix = normalizePrefix(prefix);
    }

    public static @NotNull RedissonLayer create(@NotNull RedissonClient client) {
        return named("redisson", client, "strata:redisson");
    }

    public static @NotNull RedissonLayer named(@NotNull String name, @NotNull RedissonClient client, @NotNull String prefix) {
        Objects.requireNonNull(client, "client");
        String normalized = normalizePrefix(prefix);
        return new RedissonLayer(name, client, client.getMapCache(normalized + "entries"), normalized);
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
        return Capabilities.keyValue(Role.BUFFER).withQuery(true).withIndex(true).withTtl(true);
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

    @Override
    public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> queryNow(type, query), context.executor());
    }

    private Result<Envelope> loadNow(Key<?> key) {
        Instant start = Instant.now();
        try {
            Envelope envelope = read(key.external());
            if (envelope == null) {
                return notFound(start);
            }
            if (envelope.metadata().expired()) {
                deleteByKey(key, envelope);
                return notFound(start);
            }
            return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();
        RLock lock = client.getLock(lockKey(envelope.key().external()));
        lock.lock();
        try {
            SaveCondition condition = context.saveOptions().condition();
            Envelope current = read(envelope.key().external());
            if (condition.expectedStamp() != null) {
                if (current == null || !current.stamp().equals(condition.expectedStamp())) {
                    Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }
            }

            cleanupIndexes(envelope.key().external(), current);

            byte[] bytes = serialize(envelope);
            if (context.saveOptions().ttl() == null) {
                entries.fastPut(envelope.key().external(), bytes);
            } else {
                entries.fastPut(envelope.key().external(), bytes, ttlSeconds(context.saveOptions().ttl()), TimeUnit.SECONDS);
            }

            addIndexes(envelope);
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();
        RLock lock = client.getLock(lockKey(key.external()));
        lock.lock();
        try {
            Envelope current = read(key.external());
            deleteByKey(key, current);
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> type, Query query) {
        Instant start = Instant.now();
        try {
            List<Envelope> result = new ArrayList<>();
            String candidateSetName = query.operator() == Query.Operator.EQ ? indexSet(type, query.field(), query.value()) : typeSet(type);
            RSet<String> candidates = client.getSet(candidateSetName);
            int skipped = 0;

            for (String external : candidates.readAll()) {
                Envelope envelope = read(external);
                if (envelope == null) {
                    candidates.remove(external);
                    continue;
                }
                if (envelope.metadata().expired()) {
                    deleteByKey(envelope.key(), envelope);
                    continue;
                }
                if (!envelope.key().type().id().equals(type.id())) {
                    candidates.remove(external);
                    continue;
                }
                if (!query.matches(envelope)) {
                    continue;
                }
                if (skipped < query.offset()) {
                    skipped++;
                    continue;
                }

                result.add(envelope);

                if (query.limit() > 0 && result.size() >= query.limit()) {
                    break;
                }
            }

            return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Envelope read(String external) throws Exception {
        byte[] bytes = entries.get(external);
        if (bytes == null) {
            return null;
        }
        return deserialize(bytes);
    }

    private void deleteByKey(Key<?> key, Envelope current) {
        cleanupIndexes(key.external(), current);
        entries.fastRemove(key.external());
    }

    private void cleanupIndexes(String external, Envelope current) {
        RSet<String> references = client.getSet(keyIndexesSet(external));
        for (String indexSet : references.readAll()) {
            client.getSet(indexSet).remove(external);
        }
        references.delete();

        if (current == null) {
            return;
        }

        client.getSet(typeSet(current.key().type())).remove(external);
    }

    private void addIndexes(Envelope envelope) {
        String external = envelope.key().external();
        RSet<String> typeMembers = client.getSet(typeSet(envelope.key().type()));
        RSet<String> references = client.getSet(keyIndexesSet(external));

        typeMembers.add(external);

        for (var index : envelope.metadata().indexes().entrySet()) {
            String set = indexSet(envelope.key().type(), index.getKey(), index.getValue());
            client.getSet(set).add(external);
            references.add(set);
        }
    }

    private Result<Envelope> notFound(Instant start) {
        return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
    }

    private <T> Result<T> failure(Instant start, Exception exception) {
        Failure failure = Failure.of(name, role(), exception);
        return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
    }

    private String typeSet(Type<?> type) {
        return prefix + "type:" + type.id();
    }

    private String indexSet(Type<?> type, String field, String value) {
        return prefix + "idx:" + type.id() + ":" + field + ":" + encodeToken(value);
    }

    private String keyIndexesSet(String external) {
        return prefix + "key-indexes:" + encodeToken(external);
    }

    private String lockKey(String external) {
        return prefix + "lock:" + encodeToken(external);
    }

    private static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.endsWith(":")) {
            return prefix;
        }
        return prefix + ":";
    }

    private static String encodeToken(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static long ttlSeconds(Duration duration) {
        return Math.max(1L, duration.toSeconds());
    }

    private static byte[] serialize(Envelope envelope) {
        return DefaultEnvelopeCodec.INSTANCE.encode(envelope);
    }

    private static Envelope deserialize(byte[] bytes) {
        return DefaultEnvelopeCodec.INSTANCE.decode(bytes);
    }

}