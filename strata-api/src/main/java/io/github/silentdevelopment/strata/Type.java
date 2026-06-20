package io.github.silentdevelopment.strata;


import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Objects;

/**
 * Stable logical type identifier paired with the Java value class used by codecs.
 *
 * @param <T> value type
 */
public record Type<T>(@NotNull String id, @NotNull Class<T> javaType) implements Serializable {

    public Type {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(javaType, "javaType");
        if (id.isBlank()) {
            throw new IllegalArgumentException("Type id cannot be blank.");
        }
    }

    public static <T> Type<T> of(@NotNull String id, @NotNull Class<T> javaType) {
        return new Type<>(id, javaType);
    }

}
