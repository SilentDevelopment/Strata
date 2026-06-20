package io.github.silentdevelopment.strata.core.repository;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.repository.Repository;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Default repository implementation backed by a Strata stack and key factory.
 */
public final class DefaultRepository<T, I> implements Repository<T, I> {

    private final Stack stack;
    private final Function<I, Key<T>> keyFactory;

    public DefaultRepository(@NotNull Stack stack, @NotNull Function<I, Key<T>> keyFactory) {
        this.stack = Objects.requireNonNull(stack, "stack");
        this.keyFactory = Objects.requireNonNull(keyFactory, "keyFactory");
    }

    @Override
    public @NotNull Key<T> key(@NotNull I id) {
        return keyFactory.apply(Objects.requireNonNull(id, "id"));
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> save(@NotNull I id, @NotNull T value) {
        return stack.save(key(id), value);
    }

    @Override
    public @NotNull CompletableFuture<Result<Entry<T>>> load(@NotNull I id) {
        return stack.load(key(id));
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> delete(@NotNull I id) {
        return stack.delete(key(id));
    }

    @Override
    public @NotNull CompletableFuture<Result<Entry<T>>> mutate(@NotNull I id, @NotNull UnaryOperator<T> mutator) {
        return stack.mutate(key(id), mutator);
    }

}
