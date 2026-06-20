package io.github.silentdevelopment.strata.layer;


import io.github.silentdevelopment.strata.operation.RetryPolicy;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Layer registration options, including retry policy.
 */
public record LayerOptions(@NotNull RetryPolicy retryPolicy) {

    public LayerOptions {
        Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    public static LayerOptions defaults() {
        return new LayerOptions(RetryPolicy.none());
    }

    public static LayerOptions retry(@NotNull RetryPolicy retryPolicy) {
        return new LayerOptions(retryPolicy);
    }

}
