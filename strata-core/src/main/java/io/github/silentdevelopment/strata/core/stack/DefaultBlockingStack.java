package io.github.silentdevelopment.strata.core.stack;


import io.github.silentdevelopment.strata.BlockingStack;
import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.operation.InsertOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * Default blocking stack wrapper over asynchronous stack operations.
 */
public final class DefaultBlockingStack implements BlockingStack {

    private final Stack stack;
    private final Duration timeout;

    public DefaultBlockingStack(@NotNull Stack stack, @NotNull Duration timeout) {
        this.stack = Objects.requireNonNull(stack, "stack");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
    }

    @Override
    public <T> @NotNull Result<Void> save(@NotNull Key<T> key, @NotNull T value) {
        return await(stack.save(key, value));
    }

    @Override
    public <T> @NotNull Result<Void> save(@NotNull Key<T> key, @NotNull T value, @NotNull SaveOptions options) {
        return await(stack.save(key, value, options));
    }

    @Override
    public <T> @NotNull Result<Entry<T>> load(@NotNull Key<T> key) {
        return await(stack.load(key));
    }

    @Override
    public <T> @NotNull Result<Entry<T>> load(@NotNull Key<T> key, @NotNull LoadOptions options) {
        return await(stack.load(key, options));
    }

    @Override
    public <T> @NotNull Result<Void> delete(@NotNull Key<T> key) {
        return await(stack.delete(key));
    }

    @Override
    public <T> @NotNull Result<Key<T>> insert(@NotNull Type<T> type, @NotNull T value, @NotNull InsertOptions options) {
        return await(stack.insert(type, value, options));
    }

    @Override
    public <T> @NotNull Result<List<Entry<T>>> query(@NotNull Type<T> type, @NotNull Query query) {
        return await(stack.query(type, query));
    }

    @Override
    public <T> @NotNull Result<Entry<T>> mutate(@NotNull Key<T> key, @NotNull UnaryOperator<T> mutator) {
        return await(stack.mutate(key, mutator));
    }

    private <T> Result<T> await(CompletableFuture<Result<T>> future) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("Blocking storage operation failed or timed out.", exception);
        }
    }

}
