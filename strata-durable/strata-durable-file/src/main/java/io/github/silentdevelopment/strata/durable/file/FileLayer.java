package io.github.silentdevelopment.strata.durable.file;

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

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Durable file-based layer that stores encoded envelopes on disk.
 *
 * <p>The layer is intended for simple durable storage and not high-throughput indexed workloads.</p>
 */
public final class FileLayer implements Layer {

    private final String name;
    private final Path root;

    private FileLayer(@NotNull String name, @NotNull Path root) {
        this.name = Objects.requireNonNull(name, "name");
        this.root = Objects.requireNonNull(root, "root");
    }

    public static FileLayer create(@NotNull Path root) {
        return new FileLayer("file", root);
    }

    public static FileLayer named(@NotNull String name, @NotNull Path root) {
        return new FileLayer(name, root);
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Role role() {
        return Role.DURABLE;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.keyValue(Role.DURABLE).withQuery(true).withIndex(true).withTtl(true);
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
            Path file = path(key);

            if (!Files.exists(file)) {
                return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
            }

            Envelope envelope = DefaultEnvelopeCodec.INSTANCE.decode(Files.readAllBytes(file));

            if (envelope.metadata().expired()) {
                Files.deleteIfExists(file);
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
            Envelope stored = applyTtl(envelope, context.saveOptions().ttl());
            SaveCondition condition = context.saveOptions().condition();

            if (condition.expectedStamp() != null) {
                Result<Envelope> current = loadNow(stored.key());

                if (!current.successful() || !current.valueOrThrow().stamp().equals(condition.expectedStamp())) {
                    Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                    return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
                }
            }

            Path file = path(stored.key());
            Files.createDirectories(file.getParent());

            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, DefaultEnvelopeCodec.INSTANCE.encode(stored));

            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
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
            Files.deleteIfExists(path(key));
            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> type, Query query) {
        Instant start = Instant.now();
        List<Envelope> result = new ArrayList<>();

        try {
            Path typeRoot = root.resolve(safe(type.id()));

            if (!Files.exists(typeRoot)) {
                return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
            }

            int skipped = 0;

            try (java.util.stream.Stream<Path> stream = Files.walk(typeRoot)) {
                for (Path file : stream.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".bin")).toList()) {
                    Envelope envelope = DefaultEnvelopeCodec.INSTANCE.decode(Files.readAllBytes(file));

                    if (!envelope.key().type().id().equals(type.id())) {
                        continue;
                    }

                    if (envelope.metadata().expired()) {
                        Files.deleteIfExists(file);
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
            }

            return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Envelope applyTtl(Envelope envelope, Duration ttl) {
        if (ttl == null) {
            return envelope;
        }

        return envelope.withMetadata(envelope.metadata().withExpiresAt(Instant.now().plus(ttl)));
    }

    private Path path(Key<?> key) {
        return root.resolve(safe(key.type().id())).resolve(safe(key.namespace().value())).resolve(safe(key.path()) + ".bin");
    }

    private static String safe(String value) {
        return value.replace(':', '_').replace('/', '_').replace('\\', '_');
    }

}