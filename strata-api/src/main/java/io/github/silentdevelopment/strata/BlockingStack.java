package io.github.silentdevelopment.strata;


import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.operation.InsertOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Blocking facade over an asynchronous {@link Stack}.
 *
 * <p>Each method blocks the calling thread until the underlying asynchronous
 * operation completes or the configured timeout expires. This API is intended
 * for synchronous integration points and should not be used on latency-sensitive
 * event-loop or server-main threads unless blocking is acceptable.</p>
 */
public interface BlockingStack {

    /**
     * Saves a value using default save options.
     *
     * @param key stable storage key
     * @param value domain value to encode and store
     * @param <T> value type
     * @return operation result
     */
    <T> @NotNull Result<Void> save(@NotNull Key<T> key, @NotNull T value);

    /**
     * Saves a value using explicit save options.
     *
     * @param key stable storage key
     * @param value domain value to encode and store
     * @param options write policy, condition, TTL, and index values
     * @param <T> value type
     * @return operation result
     */
    <T> @NotNull Result<Void> save(@NotNull Key<T> key, @NotNull T value, @NotNull SaveOptions options);

    /**
     * Loads a value using default load options.
     *
     * @param key stable storage key
     * @param <T> value type
     * @return decoded entry, not-found result, or failure result
     */
    <T> @NotNull Result<Entry<T>> load(@NotNull Key<T> key);

    /**
     * Loads a value using explicit load options.
     *
     * @param key stable storage key
     * @param options read policy and read-repair behavior
     * @param <T> value type
     * @return decoded entry, not-found result, or failure result
     */
    <T> @NotNull Result<Entry<T>> load(@NotNull Key<T> key, @NotNull LoadOptions options);

    /**
     * Deletes a value using default delete options.
     *
     * @param key stable storage key
     * @param <T> value type
     * @return operation result
     */
    <T> @NotNull Result<Void> delete(@NotNull Key<T> key);

    /**
     * Generates a key and saves a value under the configured namespace and collection.
     *
     * @param type value type descriptor
     * @param value domain value to encode and store
     * @param options insert namespace, collection, and save options
     * @param <T> value type
     * @return generated key or failure result
     */
    <T> @NotNull Result<Key<T>> insert(@NotNull Type<T> type, @NotNull T value, @NotNull InsertOptions options);

    /**
     * Queries entries of a type using layer-supported query capabilities.
     *
     * @param type value type descriptor
     * @param query query predicate and pagination values
     * @param <T> value type
     * @return matching decoded entries or a failure result
     */
    <T> @NotNull Result<List<Entry<T>>> query(@NotNull Type<T> type, @NotNull Query query);

    /**
     * Performs a conflict-aware load, mutate, and conditional save sequence.
     *
     * @param key stable storage key
     * @param mutator pure value transformation that may be retried
     * @param <T> value type
     * @return updated entry or failure result
     */
    <T> @NotNull Result<Entry<T>> mutate(@NotNull Key<T> key, @NotNull UnaryOperator<T> mutator);

}
