package io.github.silentdevelopment.strata.repository;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

/**
 * Typed repository facade that maps application identifiers to Strata keys.
 *
 * @param <T> stored value type
 * @param <I> application identifier type
 */
public interface Repository<T, I> {

    @NotNull Key<T> key(@NotNull I id);

    @NotNull CompletableFuture<Result<Void>> save(@NotNull I id, @NotNull T value);

    @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull I id);

    @NotNull CompletableFuture<Result<Void>> delete(@NotNull I id);

    @NotNull CompletableFuture<Result<Entry<T>>> mutate(@NotNull I id, @NotNull UnaryOperator<T> mutator);

}
