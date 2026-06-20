package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Strata;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.soft.memory.MemoryLayer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

final class MemoryLayerTest {

    private static final Type<String> STRING = Type.of("string", String.class);
    private static final Codec<String> CODEC = new Codec<>() {
        @Override
        public Encoded encode(String value) {
            return Encoded.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public String decode(Encoded encoded, Type<String> type) {
            return new String(encoded.bytes(), StandardCharsets.UTF_8);
        }
    };

    @Test
    void savesAndLoadsValue() {
        Namespace namespace = Namespace.of("test");
        Key<String> key = namespace.key(STRING, "values", "one");
        Stack stack = Strata.stack().soft(MemoryLayer.create()).codec(STRING, CODEC).build();
        stack.save(key, "hello").join();
        Result<Entry<String>> loaded = stack.load(key).join();
        assertThat(loaded.successful()).isTrue();
        assertThat(loaded.valueOrThrow().value()).isEqualTo("hello");
    }

}
