package io.github.silentdevelopment.strata.operation;


import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Delete operation options.
 */
public record DeleteOptions(@NotNull DeletePolicy deletePolicy) {

    public DeleteOptions {
        Objects.requireNonNull(deletePolicy, "deletePolicy");
    }

    public static DeleteOptions defaults() {
        return new DeleteOptions(DeletePolicy.DURABLE_INVALIDATE);
    }

}
