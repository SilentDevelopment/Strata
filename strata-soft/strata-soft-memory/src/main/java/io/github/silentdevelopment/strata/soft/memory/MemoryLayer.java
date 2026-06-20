package io.github.silentdevelopment.strata.soft.memory;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Type;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory SOFT layer with volatile key-value, TTL, query, and conditional-write behavior.
 */
public final class MemoryLayer implements Layer {

    private final String name;
    private final Map<String, Envelope> entries = new ConcurrentHashMap<>();

    private MemoryLayer(@NotNull String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public static MemoryLayer create() {
        return new MemoryLayer("memory");
    }

    public static MemoryLayer named(@NotNull String name) {
        return new MemoryLayer(name);
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Role role() {
        return Role.SOFT;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.keyValue(Role.SOFT).withQuery(true).withIndex(true).withTtl(true);
    }

    @Override
    public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
        Objects.requireNonNull(key, "key");
        Instant start = Instant.now();
        Envelope envelope = entries.get(key.external());
        if (envelope == null || envelope.metadata().expired()) {
            entries.remove(key.external());
            return CompletableFuture.completedFuture(Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of()))));
        }
        return CompletableFuture.completedFuture(Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
        Objects.requireNonNull(envelope, "envelope");
        Instant start = Instant.now();
        SaveCondition condition = context.saveOptions().condition();
        if (condition.expectedStamp() != null) {
            Envelope current = entries.get(envelope.key().external());
            if (current != null && !current.stamp().equals(condition.expectedStamp())) {
                Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                return CompletableFuture.completedFuture(Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure)))));
            }
        }
        entries.put(envelope.key().external(), envelope);
        return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
        Objects.requireNonNull(key, "key");
        Instant start = Instant.now();
        entries.remove(key.external());
        return CompletableFuture.completedFuture(Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
    }

    @Override
    public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(query, "query");
        Instant start = Instant.now();
        List<Envelope> result = new ArrayList<>();
        int skipped = 0;
        for (Envelope envelope : entries.values()) {
            if (!envelope.key().type().equals(type)) {
                continue;
            }
            if (envelope.metadata().expired()) {
                entries.remove(envelope.key().external());
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
        return CompletableFuture.completedFuture(Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now())))));
    }

    public int size() {
        return entries.size();
    }

}
