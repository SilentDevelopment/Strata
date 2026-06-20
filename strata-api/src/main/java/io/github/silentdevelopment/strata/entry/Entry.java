package io.github.silentdevelopment.strata.entry;


import io.github.silentdevelopment.strata.Key;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Decoded value returned by stack operations.
 *
 * <p>Entries are consumer-facing values produced after codec decoding. The
 * associated {@link Stamp} and {@link Metadata} preserve concurrency and
 * storage metadata needed for conditional writes and application inspection.</p>
 *
 * @param key stable storage key
 * @param value decoded domain value
 * @param stamp optimistic concurrency token
 * @param metadata storage metadata
 * @param <T> value type
 */
public record Entry<T>(@NotNull Key<T> key, @NotNull T value, @NotNull Stamp stamp, @NotNull Metadata metadata) {

    public Entry {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(metadata, "metadata");
    }

}
