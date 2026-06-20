package io.github.silentdevelopment.strata.operation;


import io.github.silentdevelopment.strata.entry.Stamp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Save operation options controlling write policy, optimistic condition, TTL, and index values.
 */
public record SaveOptions(@NotNull WritePolicy writePolicy, @NotNull SaveCondition condition, @Nullable Duration ttl, @NotNull Map<String, String> indexes) {

    public SaveOptions {
        Objects.requireNonNull(writePolicy, "writePolicy");
        Objects.requireNonNull(condition, "condition");
        indexes = Map.copyOf(Objects.requireNonNull(indexes, "indexes"));
    }

    public static SaveOptions defaults() {
        return new SaveOptions(WritePolicy.DURABLE_FIRST, SaveCondition.any(), null, Map.of());
    }

    public static SaveOptions matching(@NotNull Stamp stamp) {
        return defaults().withCondition(SaveCondition.matching(stamp));
    }

    public SaveOptions withCondition(@NotNull SaveCondition condition) {
        return new SaveOptions(writePolicy, condition, ttl, indexes);
    }

    public SaveOptions withTtl(@NotNull Duration ttl) {
        return new SaveOptions(writePolicy, condition, Objects.requireNonNull(ttl, "ttl"), indexes);
    }

    public SaveOptions withIndex(@NotNull String name, @NotNull Object value) {
        Map<String, String> copy = new LinkedHashMap<>(indexes);
        copy.put(name, String.valueOf(value));
        return new SaveOptions(writePolicy, condition, ttl, copy);
    }

    public SaveOptions withIndexes(@NotNull Map<String, String> indexes) {
        return new SaveOptions(writePolicy, condition, ttl, indexes);
    }

}
