package io.github.silentdevelopment.strata.layer;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Storage backend contract used by the stack.
 *
 * <p>Layers operate on encoded {@link Envelope} values rather than domain
 * objects. Implementations must truthfully report capabilities, must represent
 * backend failures as {@link Result#failure(Failure)} or equivalent failure
 * results, and must not silently fall back to unrelated local memory when an
 * authoritative backend fails.</p>
 */
public interface Layer {

    /**
     * Returns the stable layer name used in reports and failures.
     *
     * @return layer name
     */
    @NotNull String name();

    /**
     * Returns the role this layer performs in a stack.
     *
     * @return layer role
     */
    @NotNull Role role();

    /**
     * Reports backend features supported by this layer.
     *
     * @return truthful layer capabilities
     */
    @NotNull Capabilities capabilities();

    /**
     * Loads an encoded envelope by key.
     *
     * @param key stable storage key
     * @param context operation context and effective options
     * @return future completed with an envelope, not-found result, or failure result
     */
    @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context);

    /**
     * Saves an encoded envelope.
     *
     * @param envelope encoded stored record
     * @param context operation context and effective options
     * @return future completed with the save result
     */
    @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context);

    /**
     * Deletes or invalidates an envelope by key.
     *
     * @param key stable storage key
     * @param context operation context and effective options
     * @return future completed with the delete result
     */
    @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context);

    /**
     * Queries encoded envelopes for a type.
     *
     * <p>The default implementation returns an unsupported result. Backends
     * should override this only when safe indexed lookup or enumeration is
     * available.</p>
     *
     * @param type value type descriptor
     * @param query query predicate and pagination values
     * @param context operation context and effective options
     * @return future completed with matching envelopes or an unsupported/failure result
     */
    default @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
        return CompletableFuture.completedFuture(Result.unsupported("Layer does not support query: " + name()));
    }

}
