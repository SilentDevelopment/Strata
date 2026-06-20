package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Strata;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.durable.file.FileLayer;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class FileLayerTest {

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
    void persistsAndQueriesValue(@TempDir Path directory) {
        Namespace namespace = Namespace.of("test");
        Key<String> key = namespace.key(STRING, "values", "one");
        Stack stack = Strata.stack().durable(FileLayer.create(directory)).codec(STRING, CODEC).build();
        stack.save(key, "hello", SaveOptions.defaults().withIndex("group", "a")).join();
        Result<Entry<String>> loaded = stack.load(key).join();
        Result<List<Entry<String>>> queried = stack.query(STRING, Query.where("group").eq("a")).join();
        assertThat(loaded.valueOrThrow().value()).isEqualTo("hello");
        assertThat(queried.valueOrThrow()).hasSize(1);
    }

}
