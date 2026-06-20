package io.github.silentdevelopment.strata.operation;


import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Objects;

/**
 * Fixed retry configuration for layer operations.
 */
public record RetryPolicy(int maxAttempts, @NotNull Duration delay) {

    public RetryPolicy {
        Objects.requireNonNull(delay, "delay");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1.");
        }
    }

    public static RetryPolicy none() {
        return new RetryPolicy(1, Duration.ZERO);
    }

    public static RetryPolicy fixed(int attempts, @NotNull Duration delay) {
        return new RetryPolicy(attempts, delay);
    }

}
