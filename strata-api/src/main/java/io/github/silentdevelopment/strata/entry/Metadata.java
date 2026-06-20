package io.github.silentdevelopment.strata.entry;


import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Storage metadata attached to an envelope or decoded entry.
 */
public record Metadata(@NotNull Instant createdAt, @NotNull Instant updatedAt, @Nullable Instant expiresAt, @NotNull Map<String, String> values, @NotNull Map<String, String> indexes) implements Serializable {

    public Metadata {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
        indexes = Map.copyOf(Objects.requireNonNull(indexes, "indexes"));
    }

    public static Metadata now() {
        Instant now = Instant.now();
        return new Metadata(now, now, null, Map.of(), Map.of());
    }

    public Metadata withUpdatedAt(@NotNull Instant instant) {
        return new Metadata(createdAt, instant, expiresAt, values, indexes);
    }

    public Metadata withExpiresAt(@Nullable Instant instant) {
        return new Metadata(createdAt, updatedAt, instant, values, indexes);
    }

    public Metadata withIndexes(@NotNull Map<String, String> newIndexes) {
        return new Metadata(createdAt, updatedAt, expiresAt, values, newIndexes);
    }

    public Metadata withValue(@NotNull String key, @NotNull String value) {
        Map<String, String> copy = new LinkedHashMap<>(values);
        copy.put(key, value);
        return new Metadata(createdAt, updatedAt, expiresAt, copy, indexes);
    }

    public Optional<Instant> expiresAtOptional() {
        return Optional.ofNullable(expiresAt);
    }

    public boolean expired() {
        if (expiresAt == null) {
            return false;
        }
        return !Instant.now().isBefore(expiresAt);
    }

}
