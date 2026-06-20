package io.github.silentdevelopment.strata.codec.gson;


import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.CodecException;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Gson JSON codec for Strata payloads.
 */
public final class GsonCodec<T> implements Codec<T> {

    private final Object gson;
    private final Class<T> javaType;

    private GsonCodec(@NotNull Object gson, @NotNull Class<T> javaType) {
        this.gson = Objects.requireNonNull(gson, "gson");
        this.javaType = Objects.requireNonNull(javaType, "javaType");
    }

    public static <T> GsonCodec<T> of(@NotNull Class<T> javaType) {
        try {
            Object gson = Class.forName("com.google.gson.Gson").getConstructor().newInstance();
            return new GsonCodec<>(gson, javaType);
        } catch (Exception exception) {
            throw new CodecException("Gson is not available. Add strata-codec-gson with Gson on the runtime classpath.", exception);
        }
    }

    public static <T> GsonCodec<T> of(@NotNull Object gson, @NotNull Class<T> javaType) {
        return new GsonCodec<>(gson, javaType);
    }

    @Override
    public @NotNull Encoded encode(@NotNull T value) {
        try {
            Method toJson = gson.getClass().getMethod("toJson", Object.class);
            String json = (String) toJson.invoke(gson, value);
            return Encoded.of(json.getBytes(StandardCharsets.UTF_8), "application/json");
        } catch (Exception exception) {
            throw new CodecException("Failed to encode value using Gson.", exception);
        }
    }

    @Override
    public @NotNull T decode(@NotNull Encoded encoded, @NotNull Type<T> type) {
        try {
            Method fromJson = gson.getClass().getMethod("fromJson", String.class, Class.class);
            Object value = fromJson.invoke(gson, new String(encoded.bytes(), StandardCharsets.UTF_8), javaType);
            return type.javaType().cast(value);
        } catch (Exception exception) {
            throw new CodecException("Failed to decode value using Gson.", exception);
        }
    }

}
