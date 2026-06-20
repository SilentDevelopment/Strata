package io.github.silentdevelopment.strata;


import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Stable namespace prefix for typed storage keys.
 */
public record Namespace(@NotNull String value) implements Serializable {

    public Namespace {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Namespace cannot be blank.");
        }
    }

    public static Namespace of(@NotNull String value) {
        return new Namespace(value);
    }

    public <T> Key<T> key(@NotNull Type<T> type, @NotNull String first, @NotNull String... rest) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(rest, "rest");
        String suffix = Arrays.stream(rest).map(part -> Objects.requireNonNull(part, "key part")).collect(Collectors.joining("/"));
        String path = suffix.isBlank() ? first : first + "/" + suffix;
        return new Key<>(this, type, path);
    }

}
