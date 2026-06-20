package io.github.silentdevelopment.strata.core.stack;

import io.github.silentdevelopment.strata.BlockingStack;
import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.generator.KeyGenerator;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerRegistration;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.DeletePolicy;
import io.github.silentdevelopment.strata.operation.InsertOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.ReadPolicy;
import io.github.silentdevelopment.strata.operation.ReadRepair;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * Default asynchronous Strata stack implementation.
 */
public final class DefaultStack implements Stack {

    private static final int DEFAULT_MUTATE_ATTEMPTS = 3;

    private final Map<Role, List<LayerRegistration>> layers;
    private final Map<Type<?>, Codec<?>> codecs;
    private final Executor executor;
    private final KeyGenerator keyGenerator;
    private final OperationContext baseContext;

    public DefaultStack(@NotNull Map<Role, List<LayerRegistration>> layers, @NotNull Map<Type<?>, Codec<?>> codecs, @NotNull Executor executor, @NotNull KeyGenerator keyGenerator) {
        this.layers = copyLayers(layers);
        this.codecs = Map.copyOf(Objects.requireNonNull(codecs, "codecs"));
        this.executor = Objects.requireNonNull(executor, "executor");
        this.keyGenerator = Objects.requireNonNull(keyGenerator, "keyGenerator");
        this.baseContext = new OperationContext(executor, SaveOptions.defaults(), LoadOptions.defaults(), DeleteOptions.defaults());
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Void>> save(@NotNull Key<T> key, @NotNull T value) {
        return save(key, value, SaveOptions.defaults());
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Void>> save(@NotNull Key<T> key, @NotNull T value, @NotNull SaveOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(options, "options");
        return CompletableFuture.supplyAsync(() -> saveNow(key, value, options), executor);
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull Key<T> key) {
        return load(key, LoadOptions.defaults());
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull Key<T> key, @NotNull LoadOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        return CompletableFuture.supplyAsync(() -> loadNow(key, options), executor);
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<T> key) {
        return delete(key, DeleteOptions.defaults());
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<T> key, @NotNull DeleteOptions options) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(options, "options");
        return CompletableFuture.supplyAsync(() -> deleteNow(key, options), executor);
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Key<T>>> insert(@NotNull Type<T> type, @NotNull T value, @NotNull InsertOptions options) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(options, "options");
        return CompletableFuture.supplyAsync(() -> {
            Key<T> key = options.namespace().key(type, options.collection(), keyGenerator.generate());
            Result<Void> result = saveNow(key, value, options.saveOptions());
            if (!result.successful()) {
                return Result.failure(result.failures(), result.layers());
            }
            if (result.hasWarnings()) {
                return Result.successWithWarnings(key, result.failures(), result.layers());
            }
            return Result.success(key, result.layers());
        }, executor);
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<List<Entry<T>>>> query(@NotNull Type<T> type, @NotNull Query query) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(query, "query");
        return CompletableFuture.supplyAsync(() -> queryNow(type, query), executor);
    }

    @Override
    public <T> @NotNull CompletableFuture<Result<Entry<T>>> mutate(@NotNull Key<T> key, @NotNull UnaryOperator<T> mutator) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mutator, "mutator");
        return CompletableFuture.supplyAsync(() -> mutateNow(key, mutator), executor);
    }

    @Override
    public @NotNull BlockingStack blocking(@NotNull Duration timeout) {
        return new DefaultBlockingStack(this, timeout);
    }

    @Override
    public @NotNull Capabilities capabilities() {
        Capabilities merged = new Capabilities(false, false, false, false, false, false, false, false, false);
        for (List<LayerRegistration> registrations : layers.values()) {
            for (LayerRegistration registration : registrations) {
                merged = merged.merge(registration.layer().capabilities());
            }
        }
        return merged;
    }

    private <T> Result<Void> saveNow(Key<T> key, T value, SaveOptions options) {
        Codec<T> codec = codec(key.type());
        Encoded encoded;
        try {
            encoded = codec.encode(value);
        } catch (RuntimeException exception) {
            return Result.failure(new Failure("codec", Role.DURABLE, exception.getMessage(), exception));
        }
        Metadata metadata = Metadata.now().withIndexes(options.indexes());
        if (options.ttl() != null) {
            metadata = metadata.withExpiresAt(Instant.now().plus(options.ttl()));
        }
        Envelope envelope = new Envelope(key, encoded, Stamp.random(), metadata, false);
        OperationContext context = baseContext.withSaveOptions(options);
        return switch (options.writePolicy()) {
            case DURABLE_FIRST -> saveDurableFirst(envelope, context);
            case DURABLE_INVALIDATE -> saveDurableInvalidate(envelope, context);
            case WRITE_THROUGH -> saveWriteThrough(envelope, context);
            case ALL_REQUIRED -> saveAllRequired(envelope, context);
        };
    }

    private Result<Void> saveDurableFirst(Envelope envelope, OperationContext context) {
        List<Failure> warnings = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        List<LayerRegistration> durable = registrations(Role.DURABLE);
        if (!durable.isEmpty()) {
            Result<Void> durableResult = saveRequired(durable, envelope, context);
            reports.addAll(durableResult.layers());
            if (!durableResult.successful()) {
                return durableResult;
            }
        }
        Result<Void> softBuffer = saveBestEffort(nonDurableRegistrations(), envelope, context);
        reports.addAll(softBuffer.layers());
        warnings.addAll(softBuffer.failures());
        if (durable.isEmpty()) {
            Result<Void> fallback = saveRequired(nonDurableRegistrations(), envelope, context);
            if (!fallback.successful()) {
                return fallback;
            }
        }
        if (warnings.isEmpty()) {
            return Result.success(null, reports);
        }
        return Result.successWithWarnings(null, warnings, reports);
    }

    private Result<Void> saveDurableInvalidate(Envelope envelope, OperationContext context) {
        List<LayerRegistration> durable = registrations(Role.DURABLE);
        if (durable.isEmpty()) {
            return saveAllRequired(envelope, context);
        }
        Result<Void> durableResult = saveRequired(durable, envelope, context);
        if (!durableResult.successful()) {
            return durableResult;
        }
        List<Failure> warnings = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>(durableResult.layers());
        Result<Void> invalidation = deleteBestEffort(nonDurableRegistrations(), envelope.key(), context);
        warnings.addAll(invalidation.failures());
        reports.addAll(invalidation.layers());
        if (warnings.isEmpty()) {
            return Result.success(null, reports);
        }
        return Result.successWithWarnings(null, warnings, reports);
    }

    private Result<Void> saveWriteThrough(Envelope envelope, OperationContext context) {
        return saveDurableFirst(envelope, context);
    }

    private Result<Void> saveAllRequired(Envelope envelope, OperationContext context) {
        List<LayerRegistration> all = allRegistrations();
        if (all.isEmpty()) {
            return Result.failure(new Failure("stack", Role.DURABLE, "No layers configured.", null));
        }
        return saveRequired(all, envelope, context);
    }

    private <T> Result<Entry<T>> loadNow(Key<T> key, LoadOptions options) {
        OperationContext context = baseContext.withLoadOptions(options);

        if (options.readPolicy() == ReadPolicy.FIRST_AVAILABLE || options.readPolicy() == ReadPolicy.SOFT_BUFFER_DURABLE) {
            return loadFirstAvailable(key, context, options);
        }

        List<LayerRegistration> durable = registrations(Role.DURABLE);

        if (!durable.isEmpty()) {
            Result<Envelope> durableResult = loadFirstEnvelope(durable, key, context);

            if (!durableResult.successful()) {
                if (durableResult.status() == Status.NOT_FOUND) {
                    return Result.notFound(durableResult.layers());
                }

                return Result.failure(durableResult.failures(), durableResult.layers());
            }

            Envelope envelope = durableResult.valueOrThrow();
            Result<Entry<T>> decoded = decode(key, envelope, durableResult.layers());

            if (!decoded.successful()) {
                return decoded;
            }

            if (options.readRepair() == ReadRepair.DISABLED) {
                return decoded;
            }

            Result<Void> repair = saveBestEffort(nonDurableRegistrations(), envelope, context);
            List<io.github.silentdevelopment.strata.layer.LayerReport> reports = concat(decoded.layers(), repair.layers());

            if (options.readRepair() == ReadRepair.REQUIRED && !repair.failures().isEmpty()) {
                return Result.failure(repair.failures(), reports);
            }

            if (!repair.failures().isEmpty()) {
                return decoded.withWarnings(repair.failures(), reports);
            }

            return decoded.withLayers(reports);
        }

        return loadFirstAvailable(key, context, options);
    }

    private <T> Result<Entry<T>> loadFirstAvailable(Key<T> key, OperationContext context, LoadOptions options) {
        Result<Envelope> result = loadFirstEnvelope(allRegistrationsInReadOrder(), key, context);
        if (!result.successful()) {
            if (result.status() == Status.NOT_FOUND) {
                return Result.notFound(result.layers());
            }
            return Result.failure(result.failures(), result.layers());
        }
        return decode(key, result.valueOrThrow(), result.layers());
    }

    private <T> Result<Void> deleteNow(Key<T> key, DeleteOptions options) {
        OperationContext context = baseContext.withDeleteOptions(options);
        if (options.deletePolicy() == DeletePolicy.ALL) {
            return deleteRequired(allRegistrations(), key, context);
        }
        if (options.deletePolicy() == DeletePolicy.DURABLE_ONLY) {
            return deleteRequired(registrations(Role.DURABLE), key, context);
        }
        Result<Void> durableResult = deleteRequiredOrFallback(registrations(Role.DURABLE), key, context);
        if (!durableResult.successful()) {
            return durableResult;
        }
        if (options.deletePolicy() == DeletePolicy.TOMBSTONE) {
            return durableResult;
        }
        Result<Void> invalidation = deleteBestEffort(nonDurableRegistrations(), key, context);
        return durableResult.withWarnings(invalidation.failures(), concat(durableResult.layers(), invalidation.layers()));
    }

    private <T> Result<List<Entry<T>>> queryNow(Type<T> type, Query query) {
        OperationContext context = baseContext;
        List<LayerRegistration> candidates = !registrations(Role.DURABLE).isEmpty() ? registrations(Role.DURABLE) : allRegistrationsInReadOrder();
        for (LayerRegistration registration : candidates) {
            Layer layer = registration.layer();
            Result<List<Envelope>> envelopes = layer.query(type, query, context).join();
            if (!envelopes.successful()) {
                continue;
            }
            List<Entry<T>> entries = new ArrayList<>();
            for (Envelope envelope : envelopes.valueOrThrow()) {
                Result<Entry<T>> decoded = decode(Key.of(envelope.key().namespace(), type, envelope.key().path()), envelope, envelopes.layers());
                if (decoded.successful()) {
                    entries.add(decoded.valueOrThrow());
                }
            }
            return Result.success(entries, envelopes.layers());
        }
        return Result.unsupported("No configured layer supports query for type " + type.id());
    }

    private <T> Result<Entry<T>> mutateNow(Key<T> key, UnaryOperator<T> mutator) {
        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();

        for (int attempt = 0; attempt < DEFAULT_MUTATE_ATTEMPTS; attempt++) {
            Result<Entry<T>> loaded = loadNow(key, LoadOptions.defaults());

            if (!loaded.successful()) {
                return loaded;
            }

            Entry<T> entry = loaded.valueOrThrow();
            T changed = mutator.apply(entry.value());
            Result<Void> saved = saveNow(key, changed, SaveOptions.matching(entry.stamp()).withIndexes(entry.metadata().indexes()));

            reports.addAll(saved.layers());

            if (saved.successful()) {
                return loadNow(key, LoadOptions.defaults());
            }

            if (saved.status() != Status.CONFLICT) {
                return Result.failure(saved.failures(), saved.layers());
            }

            failures.addAll(saved.failures());
        }

        if (failures.isEmpty()) {
            failures.add(new Failure("stack", Role.DURABLE, "Mutation conflicted too many times.", null));
        }

        return Result.conflict(failures, reports);
    }

    private Result<Void> saveRequired(List<LayerRegistration> registrations, Envelope envelope, OperationContext context) {
        if (registrations.isEmpty()) {
            return Result.success(null);
        }

        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        boolean conflict = false;

        for (LayerRegistration registration : registrations) {
            Result<Void> result = registration.layer().save(envelope, context).join();
            reports.addAll(result.layers());

            if (result.successful()) {
                continue;
            }

            failures.addAll(result.failures());

            if (result.status() == Status.CONFLICT) {
                conflict = true;
            }
        }

        if (failures.isEmpty()) {
            return Result.success(null, reports);
        }

        if (conflict) {
            return Result.conflict(failures, reports);
        }

        return Result.failure(failures, reports);
    }

    private Result<Void> saveBestEffort(List<LayerRegistration> registrations, Envelope envelope, OperationContext context) {
        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        for (LayerRegistration registration : registrations) {
            try {
                Result<Void> result = registration.layer().save(envelope, context).join();
                failures.addAll(result.failures());
                reports.addAll(result.layers());
            } catch (RuntimeException exception) {
                failures.add(Failure.of(registration.layer().name(), registration.layer().role(), exception));
            }
        }
        if (failures.isEmpty()) {
            return Result.success(null, reports);
        }
        return Result.successWithWarnings(null, failures, reports);
    }

    private Result<Void> deleteRequiredOrFallback(List<LayerRegistration> registrations, Key<?> key, OperationContext context) {
        if (registrations.isEmpty()) {
            return deleteRequired(nonDurableRegistrations(), key, context);
        }
        return deleteRequired(registrations, key, context);
    }

    private Result<Void> deleteRequired(List<LayerRegistration> registrations, Key<?> key, OperationContext context) {
        if (registrations.isEmpty()) {
            return Result.success(null);
        }
        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        for (LayerRegistration registration : registrations) {
            Result<Void> result = registration.layer().delete(key, context).join();
            reports.addAll(result.layers());
            if (!result.successful()) {
                failures.addAll(result.failures());
            }
        }
        if (failures.isEmpty()) {
            return Result.success(null, reports);
        }
        return Result.failure(failures, reports);
    }

    private Result<Void> deleteBestEffort(List<LayerRegistration> registrations, Key<?> key, OperationContext context) {
        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        for (LayerRegistration registration : registrations) {
            Result<Void> result = registration.layer().delete(key, context).join();
            reports.addAll(result.layers());
            failures.addAll(result.failures());
        }
        if (failures.isEmpty()) {
            return Result.success(null, reports);
        }
        return Result.successWithWarnings(null, failures, reports);
    }

    private Result<Envelope> loadFirstEnvelope(List<LayerRegistration> registrations, Key<?> key, OperationContext context) {
        List<Failure> failures = new ArrayList<>();
        List<io.github.silentdevelopment.strata.layer.LayerReport> reports = new ArrayList<>();
        for (LayerRegistration registration : registrations) {
            Result<Envelope> result = registration.layer().load(key, context).join();
            reports.addAll(result.layers());
            if (result.successful()) {
                return result.withLayers(reports);
            }
            if (result.status() != Status.NOT_FOUND) {
                failures.addAll(result.failures());
            }
        }
        if (failures.isEmpty()) {
            return Result.notFound(reports);
        }
        return Result.failure(failures, reports);
    }

    private <T> Result<Entry<T>> decode(Key<T> key, Envelope envelope, List<io.github.silentdevelopment.strata.layer.LayerReport> reports) {
        if (envelope.tombstone()) {
            return Result.notFound(reports);
        }
        if (envelope.metadata().expired()) {
            return Result.notFound(reports);
        }
        Codec<T> codec = codec(key.type());
        try {
            T value = codec.decode(envelope.encoded(), key.type());
            return Result.success(new Entry<>(key, value, envelope.stamp(), envelope.metadata()), reports);
        } catch (RuntimeException exception) {
            return Result.failure(new Failure("codec", Role.DURABLE, exception.getMessage(), exception));
        }
    }

    @SuppressWarnings("unchecked")
    private <T> Codec<T> codec(Type<T> type) {
        Codec<?> codec = codecs.get(type);
        if (codec == null) {
            throw new IllegalStateException("No codec registered for type: " + type.id());
        }
        return (Codec<T>) codec;
    }

    private List<LayerRegistration> registrations(Role role) {
        return layers.getOrDefault(role, List.of());
    }

    private List<LayerRegistration> nonDurableRegistrations() {
        List<LayerRegistration> result = new ArrayList<>();
        result.addAll(registrations(Role.SOFT));
        result.addAll(registrations(Role.BUFFER));
        return result;
    }

    private List<LayerRegistration> allRegistrationsInReadOrder() {
        List<LayerRegistration> result = new ArrayList<>();
        result.addAll(registrations(Role.SOFT));
        result.addAll(registrations(Role.BUFFER));
        result.addAll(registrations(Role.DURABLE));
        return result;
    }

    private List<LayerRegistration> allRegistrations() {
        List<LayerRegistration> result = new ArrayList<>();
        result.addAll(registrations(Role.SOFT));
        result.addAll(registrations(Role.BUFFER));
        result.addAll(registrations(Role.DURABLE));
        return result;
    }

    private static Map<Role, List<LayerRegistration>> copyLayers(Map<Role, List<LayerRegistration>> source) {
        Map<Role, List<LayerRegistration>> result = new EnumMap<>(Role.class);
        for (Role role : Role.values()) {
            result.put(role, List.copyOf(source.getOrDefault(role, List.of())));
        }
        return result;
    }

    private static <T> List<T> concat(List<T> left, List<T> right) {
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        List<T> result = new ArrayList<>(left);
        result.addAll(right);
        return result;
    }

}
