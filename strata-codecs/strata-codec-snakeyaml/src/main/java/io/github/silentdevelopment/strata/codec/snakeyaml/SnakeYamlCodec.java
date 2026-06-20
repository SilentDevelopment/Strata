package io.github.silentdevelopment.strata.codec.snakeyaml;


import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.CodecException;
import io.github.silentdevelopment.strata.codec.Encoded;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * SnakeYAML codec for YAML payloads.
 */
public final class SnakeYamlCodec<T> implements Codec<T> {

    private final Object yaml;
    private final Class<T> javaType;

    private SnakeYamlCodec(@NotNull Object yaml, @NotNull Class<T> javaType) {
        this.yaml = Objects.requireNonNull(yaml, "yaml");
        this.javaType = Objects.requireNonNull(javaType, "javaType");
    }

    public static <T> SnakeYamlCodec<T> of(@NotNull Class<T> javaType) {
        try {
            Object yaml = Class.forName("org.yaml.snakeyaml.Yaml").getConstructor().newInstance();
            return new SnakeYamlCodec<>(yaml, javaType);
        } catch (Exception exception) {
            throw new CodecException("SnakeYAML is not available. Add strata-codec-snakeyaml with SnakeYAML on the runtime classpath.", exception);
        }
    }

    public static <T> SnakeYamlCodec<T> of(@NotNull Object yaml, @NotNull Class<T> javaType) {
        return new SnakeYamlCodec<>(yaml, javaType);
    }

    @Override
    public @NotNull Encoded encode(@NotNull T value) {
        try {
            Method dump = yaml.getClass().getMethod("dump", Object.class);
            String data = (String) dump.invoke(yaml, value);
            return Encoded.of(data.getBytes(StandardCharsets.UTF_8), "application/x-yaml");
        } catch (Exception exception) {
            throw new CodecException("Failed to encode value using SnakeYAML.", exception);
        }
    }

    @Override
    public @NotNull T decode(@NotNull Encoded encoded, @NotNull Type<T> type) {
        try {
            Method loadAs = yaml.getClass().getMethod("loadAs", String.class, Class.class);
            Object value = loadAs.invoke(yaml, new String(encoded.bytes(), StandardCharsets.UTF_8), javaType);
            return type.javaType().cast(value);
        } catch (Exception exception) {
            throw new CodecException("Failed to decode value using SnakeYAML.", exception);
        }
    }

}
