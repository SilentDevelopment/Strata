package io.github.silentdevelopment.strata.codec.jdk;


import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.CodecException;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Codec backed by Java object serialization.
 *
 * <p>Payload compatibility follows the Java serialization contract of encoded classes.</p>
 */
public final class JdkCodec<T extends Serializable> implements Codec<T> {

    public static <T extends Serializable> JdkCodec<T> create() {
        return new JdkCodec<>();
    }

    @Override
    public @NotNull Encoded encode(@NotNull T value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
                output.writeObject(value);
            }
            return Encoded.of(bytes.toByteArray(), "application/x-java-serialized-object");
        } catch (Exception exception) {
            throw new CodecException("Failed to encode value using Java serialization.", exception);
        }
    }

    @Override
    public @NotNull T decode(@NotNull Encoded encoded, @NotNull Type<T> type) {
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(encoded.bytes()))) {
            Object value = input.readObject();
            return type.javaType().cast(value);
        } catch (Exception exception) {
            throw new CodecException("Failed to decode value using Java serialization.", exception);
        }
    }

}
