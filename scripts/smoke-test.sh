#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="${TMPDIR:-/tmp}/strata-smoke"
rm -rf "$TMP"
mkdir -p "$TMP/stubs/org/jetbrains/annotations" "$TMP/classes"
cat > "$TMP/stubs/org/jetbrains/annotations/NotNull.java" <<'JAVA'
package org.jetbrains.annotations;
public @interface NotNull {}
JAVA
cat > "$TMP/stubs/org/jetbrains/annotations/Nullable.java" <<'JAVA'
package org.jetbrains.annotations;
public @interface Nullable {}
JAVA
javac --release 21 -d "$TMP/classes" $(find "$TMP/stubs" -name '*.java') $(find "$ROOT" -path '*/src/main/java/*.java' -o -path '*/src/main/java/**/*.java')
cat > "$TMP/StrataSmokeTest.java" <<'JAVA'
import io.github.silentdevelopment.strata.*;
import io.github.silentdevelopment.strata.soft.memory.MemoryLayer;
import io.github.silentdevelopment.strata.durable.file.FileLayer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
public class StrataSmokeTest {
    static final Type<String> STRING = Type.of("string", String.class);
    static final Codec<String> CODEC = new Codec<>() {
        public Encoded encode(String value) { return Encoded.of(value.getBytes(StandardCharsets.UTF_8), "text/plain"); }
        public String decode(Encoded encoded, Type<String> type) { return new String(encoded.bytes(), StandardCharsets.UTF_8); }
    };
    public static void main(String[] args) throws Exception {
        Namespace ns = Namespace.of("test");
        Key<String> key = ns.key(STRING, "values", "one");
        Stack stack = Strata.stack().soft(MemoryLayer.create()).durable(FileLayer.create(Files.createTempDirectory("strata-smoke"))).codec(STRING, CODEC).build();
        if (!stack.save(key, "hello", SaveOptions.defaults().withIndex("group", "a")).join().successful()) throw new AssertionError("save failed");
        if (!stack.load(key).join().valueOrThrow().value().equals("hello")) throw new AssertionError("load failed");
        if (stack.findByIndex(STRING, "group", "a").join().valueOrThrow().size() != 1) throw new AssertionError("query failed");
        if (!stack.mutate(key, value -> value + " world").join().valueOrThrow().value().equals("hello world")) throw new AssertionError("mutate failed");
        if (!stack.delete(key).join().successful()) throw new AssertionError("delete failed");
        if (stack.load(key).join().status() != Status.NOT_FOUND) throw new AssertionError("not found failed");
        System.out.println("Strata smoke test passed.");
    }
}
JAVA
javac --release 21 -cp "$TMP/classes" -d "$TMP/classes" "$TMP/StrataSmokeTest.java"
java -cp "$TMP/classes" StrataSmokeTest
