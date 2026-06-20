package io.github.silentdevelopment.strata.entry;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Objects;

/**
 * Encoded stored record exchanged with layers.
 *
 * <p>An envelope contains the persistent key, encoded payload, optimistic
 * concurrency stamp, metadata, and tombstone flag. Layer implementations store
 * and load envelopes without decoding domain values.</p>
 *
 * @param key stable storage key
 * @param encoded encoded payload
 * @param stamp optimistic concurrency token
 * @param metadata storage metadata
 * @param tombstone whether the record represents a delete marker
 */
public record Envelope(@NotNull Key<?> key, @NotNull Encoded encoded, @NotNull Stamp stamp, @NotNull Metadata metadata, boolean tombstone) implements Serializable {

    public Envelope {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(stamp, "stamp");
        Objects.requireNonNull(metadata, "metadata");
    }

    /**
     * Returns a copy with a different optimistic concurrency stamp.
     *
     * @param stamp replacement stamp
     * @return updated envelope
     */
    public Envelope withStamp(@NotNull Stamp stamp) {
        return new Envelope(key, encoded, stamp, metadata, tombstone);
    }

    /**
     * Returns a copy with different metadata.
     *
     * @param metadata replacement metadata
     * @return updated envelope
     */
    public Envelope withMetadata(@NotNull Metadata metadata) {
        return new Envelope(key, encoded, stamp, metadata, tombstone);
    }

}
