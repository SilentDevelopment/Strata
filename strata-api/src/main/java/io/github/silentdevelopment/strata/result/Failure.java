package io.github.silentdevelopment.strata.result;


import io.github.silentdevelopment.strata.layer.Role;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Failure detail reported by a stack or layer operation.
 */
public record Failure(@NotNull String layer, @NotNull Role role, @NotNull String message, @Nullable Throwable cause) {

    public Failure {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(message, "message");
    }

    public static Failure of(@NotNull String layer, @NotNull Role role, @NotNull Throwable cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        return new Failure(layer, role, message, cause);
    }

}
