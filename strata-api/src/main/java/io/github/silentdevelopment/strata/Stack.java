package io.github.silentdevelopment.strata;


import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.InsertOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/**
 * Asynchronous facade for a layered Strata storage stack.
 *
 * <p>The stack owns codec application, layer ordering, read repair, write
 * policy execution, and result aggregation. Expected backend failures are
 * returned as {@link Result} values. Programming errors, invalid arguments, and
 * invalid stack configuration may throw exceptions.</p>
 *
 * <p>Durable storage is authoritative by default. Policies in
 * {@link SaveOptions}, {@link LoadOptions}, and {@link DeleteOptions} control
 * when soft and buffer layers participate.</p>
 */
public interface Stack {

    /**
     * Saves a value using default save options.
     *
     * @param key stable storage key
     * @param value domain value to encode and store
     * @param <T> value type
     * @return future completed with the operation result
     */
    <T> @NotNull CompletableFuture<Result<Void>> save(@NotNull Key<T> key, @NotNull T value);

    /**
     * Saves a value using explicit save options.
     *
     * <p>The returned future completes when the selected write policy has
     * finished. Expected storage failures are represented by the returned
     * {@link Result}.</p>
     *
     * @param key stable storage key
     * @param value domain value to encode and store
     * @param options write policy, condition, TTL, and index values
     * @param <T> value type
     * @return future completed with the operation result
     */
    <T> @NotNull CompletableFuture<Result<Void>> save(@NotNull Key<T> key, @NotNull T value, @NotNull SaveOptions options);

    /**
     * Loads a value using default load options.
     *
     * @param key stable storage key
     * @param <T> value type
     * @return future completed with a decoded entry, not-found result, or failure result
     */
    <T> @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull Key<T> key);

    /**
     * Loads a value using explicit load options.
     *
     * @param key stable storage key
     * @param options read policy and read-repair behavior
     * @param <T> value type
     * @return future completed with a decoded entry, not-found result, or failure result
     */
    <T> @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull Key<T> key, @NotNull LoadOptions options);

    /**
     * Deletes a value using default delete options.
     *
     * @param key stable storage key
     * @param <T> value type
     * @return future completed with the delete result
     */
    <T> @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<T> key);

    /**
     * Deletes a value using explicit delete options.
     *
     * @param key stable storage key
     * @param options delete propagation policy
     * @param <T> value type
     * @return future completed with the delete result
     */
    <T> @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<T> key, @NotNull DeleteOptions options);

    /**
     * Generates a key and saves a value under the configured namespace and collection.
     *
     * @param type value type descriptor
     * @param value domain value to encode and store
     * @param options insert namespace, collection, and save options
     * @param <T> value type
     * @return future completed with the generated key or a failure result
     */
    <T> @NotNull CompletableFuture<Result<Key<T>>> insert(@NotNull Type<T> type, @NotNull T value, @NotNull InsertOptions options);

    /**
     * Queries entries of a type using layer-supported query capabilities.
     *
     * <p>Query availability is backend dependent. Layers without query support
     * return unsupported results rather than performing unsafe enumeration.</p>
     *
     * @param type value type descriptor
     * @param query query predicate and pagination values
     * @param <T> value type
     * @return future completed with matching decoded entries or a failure result
     */
    <T> @NotNull CompletableFuture<Result<List<Entry<T>>>> query(@NotNull Type<T> type, @NotNull Query query);

    /**
     * Finds all entries of the given type using {@link Query#all()}.
     *
     * @param type value type descriptor
     * @param <T> value type
     * @return future completed with matching decoded entries or a failure result
     */
    default <T> @NotNull CompletableFuture<Result<List<Entry<T>>>> findAll(@NotNull Type<T> type) {
        return query(type, Query.all());
    }

    /**
     * Finds entries whose indexed field equals the supplied value.
     *
     * @param type value type descriptor
     * @param index index field name
     * @param value index value
     * @param <T> value type
     * @return future completed with matching decoded entries or a failure result
     */
    default <T> @NotNull CompletableFuture<Result<List<Entry<T>>>> findByIndex(@NotNull Type<T> type, @NotNull String index, @NotNull Object value) {
        return query(type, Query.where(index).eq(value));
    }

    /**
     * Performs a conflict-aware load, mutate, and conditional save sequence.
     *
     * <p>The mutation function may be invoked more than once when optimistic
     * concurrency detects a stale write. Mutation functions should not perform
     * external side effects.</p>
     *
     * @param key stable storage key
     * @param mutator pure value transformation
     * @param <T> value type
     * @return future completed with the updated entry or a failure result
     */
    <T> @NotNull CompletableFuture<Result<Entry<T>>> mutate(@NotNull Key<T> key, @NotNull UnaryOperator<T> mutator);

    /**
     * Creates a blocking wrapper over this stack.
     *
     * @param timeout maximum duration to wait for each operation
     * @return blocking stack wrapper
     */
    @NotNull BlockingStack blocking(@NotNull Duration timeout);

    /**
     * Reports aggregate capabilities exposed by the configured layers.
     *
     * @return merged stack capabilities
     */
    @NotNull Capabilities capabilities();

}
