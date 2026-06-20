package io.github.silentdevelopment.strata.codec;


import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable encoded payload and content type pair stored inside an envelope.
 */
public final class Encoded implements Serializable {

    private final byte[] bytes;
    private final String contentType;

    public Encoded(@NotNull byte[] bytes, @NotNull String contentType) {
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.contentType = Objects.requireNonNull(contentType, "contentType");
    }

    public static Encoded of(@NotNull byte[] bytes, @NotNull String contentType) {
        return new Encoded(bytes, contentType);
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public String contentType() {
        return contentType;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Encoded encoded)) {
            return false;
        }
        return Arrays.equals(bytes, encoded.bytes) && contentType.equals(encoded.contentType);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(bytes) + contentType.hashCode();
    }

}
