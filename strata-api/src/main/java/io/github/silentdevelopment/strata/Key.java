package io.github.silentdevelopment.strata;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Objects;

/**
 * Represents a typed storage key within a namespace.
 *
 * <p>A key is the persistent identity used by all storage layers. The identity is based on explicit namespace, type, and path values.</p>
 *
 * @param <T> value type associated with this key
 */
public record Key<T>(@NotNull Namespace namespace, @NotNull Type<T> type, @NotNull String path) implements Serializable {

    public Key {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(path, "path");
        if (path.isBlank()) {
            throw new IllegalArgumentException("Key path cannot be blank.");
        }
    }

    public static <T> Key<T> of(@NotNull Namespace namespace, @NotNull Type<T> type, @NotNull String path) {
        return new Key<>(namespace, type, path);
    }

    public String external() {
        return namespace.value() + ":" + type.id() + ":" + path.replace('/', ':');
    }

}
