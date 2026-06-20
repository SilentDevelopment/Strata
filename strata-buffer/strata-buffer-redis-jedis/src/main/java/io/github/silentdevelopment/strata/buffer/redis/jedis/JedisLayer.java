package io.github.silentdevelopment.strata.buffer.redis.jedis;

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
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Jedis-backed Redis BUFFER layer using Strata-maintained registry and index sets.
 *
 * <p>Query support is based on maintained sets and does not use Redis KEYS.</p>
 */
public final class JedisLayer implements Layer {

    private static final int DEFAULT_SCAN_COUNT = 512;

    private static final String SAVE_SCRIPT = """
        local expected = ARGV[1]
        local payload = ARGV[2]
        local stamp = ARGV[3]
        local ttl = tonumber(ARGV[4])
        local external = ARGV[5]

        if expected ~= "" then
            local current = redis.call("GET", KEYS[2])
            if not current or current ~= expected then
                return 0
            end
        end

        local previous = redis.call("SMEMBERS", KEYS[4])
        for _, setKey in ipairs(previous) do
            redis.call("SREM", setKey, external)
        end

        redis.call("DEL", KEYS[4])
        redis.call("SET", KEYS[1], payload)
        redis.call("SET", KEYS[2], stamp)
        redis.call("SADD", KEYS[3], external)

        for i = 6, #ARGV do
            redis.call("SADD", ARGV[i], external)
            redis.call("SADD", KEYS[4], ARGV[i])
        end

        if ttl > 0 then
            redis.call("EXPIRE", KEYS[1], ttl)
            redis.call("EXPIRE", KEYS[2], ttl)
            redis.call("EXPIRE", KEYS[4], ttl)
        end

        return 1
        """;

    private static final String DELETE_SCRIPT = """
        local external = ARGV[1]
        local previous = redis.call("SMEMBERS", KEYS[4])

        for _, setKey in ipairs(previous) do
            redis.call("SREM", setKey, external)
        end

        redis.call("DEL", KEYS[1])
        redis.call("DEL", KEYS[2])
        redis.call("DEL", KEYS[4])
        redis.call("SREM", KEYS[3], external)

        return 1
        """;

    private final String name;
    private final JedisPooled jedis;
    private final String prefix;
    private final int scanCount;

    private JedisLayer(@NotNull String name, @NotNull JedisPooled jedis, @NotNull String prefix, int scanCount) {
        this.name = Objects.requireNonNull(name, "name");
        this.jedis = Objects.requireNonNull(jedis, "jedis");
        this.prefix = normalizePrefix(prefix);
        this.scanCount = scanCount;
    }

    public static @NotNull JedisLayer create(@NotNull JedisPooled jedis) {
        return named("jedis", jedis, "strata:jedis");
    }

    public static @NotNull JedisLayer named(@NotNull String name, @NotNull JedisPooled jedis, @NotNull String prefix) {
        return new JedisLayer(name, jedis, prefix, DEFAULT_SCAN_COUNT);
    }

    public @NotNull JedisLayer withScanCount(int scanCount) {
        if (scanCount <= 0) {
            throw new IllegalArgumentException("scanCount must be positive.");
        }
        return new JedisLayer(name, jedis, prefix, scanCount);
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
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }
            if (envelope.metadata().expired()) {
                deleteNow(key);
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }
            return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();
        try {
            SaveCondition condition = context.saveOptions().condition();
            List<String> indexSets = indexSets(envelope);
            List<String> keys = List.of(entryKey(envelope.key().external()), stampKey(envelope.key().external()), typeSet(envelope.key().type()), keyIndexesSet(envelope.key().external()));
            List<String> args = new ArrayList<>();
            args.add(condition.expectedStamp() == null ? "" : condition.expectedStamp().value());
            args.add(Base64.getEncoder().encodeToString(serialize(envelope)));
            args.add(envelope.stamp().value());
            args.add(String.valueOf(context.saveOptions().ttl() == null ? 0L : ttlSeconds(context.saveOptions().ttl())));
            args.add(envelope.key().external());
            args.addAll(indexSets);

            Object saved = jedis.eval(SAVE_SCRIPT, keys, args);
            if (!(saved instanceof Long value) || value == 0L) {
                Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();
        try {
            List<String> keys = List.of(entryKey(key.external()), stampKey(key.external()), typeSet(key.type()), keyIndexesSet(key.external()));
            jedis.eval(DELETE_SCRIPT, keys, List.of(key.external()));
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> type, Query query) {
        Instant start = Instant.now();
        try {
            List<Envelope> result = new ArrayList<>();
            String candidateSet = query.operator() == Query.Operator.EQ ? indexSet(type, query.field(), query.value()) : typeSet(type);
            int[] skipped = {0};

            scanSet(candidateSet, external -> {
                if (query.limit() > 0 && result.size() >= query.limit()) {
                    return;
                }

                Envelope envelope = readQuietly(external);
                if (envelope == null) {
                    cleanupCandidate(type, query, external);
                    return;
                }
                if (envelope.metadata().expired()) {
                    deleteNow(envelope.key());
                    return;
                }
                if (!envelope.key().type().id().equals(type.id())) {
                    cleanupCandidate(type, query, external);
                    return;
                }
                if (!query.matches(envelope)) {
                    return;
                }
                if (skipped[0] < query.offset()) {
                    skipped[0]++;
                    return;
                }

                result.add(envelope);
            });

            return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Envelope read(String externalKey) throws Exception {
        String value = jedis.get(entryKey(externalKey));
        if (value == null) {
            return null;
        }
        return deserialize(Base64.getDecoder().decode(value));
    }

    private Envelope readQuietly(String externalKey) {
        try {
            return read(externalKey);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void scanSet(String set, Consumer<String> consumer) {
        String cursor = ScanParams.SCAN_POINTER_START;
        ScanParams params = new ScanParams().count(scanCount);
        do {
            ScanResult<String> result = jedis.sscan(set, cursor, params);
            for (String value : result.getResult()) {
                consumer.accept(value);
            }
            cursor = result.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
    }

    private void cleanupCandidate(Type<?> type, Query query, String external) {
        jedis.srem(typeSet(type), external);
        if (query.operator() == Query.Operator.EQ) {
            jedis.srem(indexSet(type, query.field(), query.value()), external);
        }
    }

    private List<String> indexSets(Envelope envelope) {
        List<String> result = new ArrayList<>();
        for (var index : envelope.metadata().indexes().entrySet()) {
            result.add(indexSet(envelope.key().type(), index.getKey(), index.getValue()));
        }
        return result;
    }

    private <T> Result<T> failure(Instant start, Exception exception) {
        Failure failure = Failure.of(name, role(), exception);
        return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
    }

    private String entryKey(String externalKey) {
        return prefix + "entry:" + externalKey;
    }

    private String stampKey(String externalKey) {
        return prefix + "stamp:" + externalKey;
    }

    private String typeSet(Type<?> type) {
        return prefix + "type:" + type.id();
    }

    private String indexSet(Type<?> type, String field, String value) {
        return prefix + "idx:" + type.id() + ":" + field + ":" + encodeToken(value);
    }

    private String keyIndexesSet(String externalKey) {
        return prefix + "key-indexes:" + encodeToken(externalKey);
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