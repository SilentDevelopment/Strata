package io.github.silentdevelopment.strata.codec;


import io.github.silentdevelopment.strata.Type;
import org.jetbrains.annotations.NotNull;

/**
 * Encodes and decodes domain values for storage envelopes.
 *
 * @param <T> domain value type
 */
public interface Codec<T> {

    @NotNull Encoded encode(@NotNull T value);

    @NotNull T decode(@NotNull Encoded encoded, @NotNull Type<T> type);

}
