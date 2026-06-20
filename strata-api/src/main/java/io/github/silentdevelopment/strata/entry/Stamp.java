package io.github.silentdevelopment.strata.entry;


import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque optimistic concurrency token associated with an envelope.
 *
 * <p>Stamp values are compared for equality by stack and layer implementations
 * during conditional saves. They are not numeric versions and should not be
 * interpreted for ordering by consumers.</p>
 *
 * @param value opaque stamp value
 */
public record Stamp(@NotNull String value) implements Serializable {

    /**
     * Sentinel stamp for records without a backend-provided token.
     */
    public static final Stamp NONE = new Stamp("none");

    public Stamp {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Stamp value cannot be blank.");
        }
    }

    /**
     * Creates a random stamp value.
     *
     * @return random stamp
     */
    public static Stamp random() {
        return new Stamp(UUID.randomUUID().toString());
    }

    /**
     * Creates a stamp from an explicit opaque value.
     *
     * @param value stamp value
     * @return stamp
     */
    public static Stamp of(@NotNull String value) {
        return new Stamp(value);
    }

}
