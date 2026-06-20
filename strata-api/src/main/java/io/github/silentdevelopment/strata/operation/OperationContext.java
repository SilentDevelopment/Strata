package io.github.silentdevelopment.strata.operation;


import org.jetbrains.annotations.NotNull;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Effective execution context passed from the stack to layers.
 */
public record OperationContext(@NotNull Executor executor, @NotNull SaveOptions saveOptions, @NotNull LoadOptions loadOptions, @NotNull DeleteOptions deleteOptions) {

    public OperationContext {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(saveOptions, "saveOptions");
        Objects.requireNonNull(loadOptions, "loadOptions");
        Objects.requireNonNull(deleteOptions, "deleteOptions");
    }

    public OperationContext withSaveOptions(@NotNull SaveOptions options) {
        return new OperationContext(executor, options, loadOptions, deleteOptions);
    }

    public OperationContext withLoadOptions(@NotNull LoadOptions options) {
        return new OperationContext(executor, saveOptions, options, deleteOptions);
    }

    public OperationContext withDeleteOptions(@NotNull DeleteOptions options) {
        return new OperationContext(executor, saveOptions, loadOptions, options);
    }

}
