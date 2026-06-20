package io.github.silentdevelopment.strata.testkit;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Reusable contract tests for external Strata layer implementations.
 */
public final class ExternalLayerContract {

    private static final Type<String> TYPE = Type.of("contract_string", String.class);
    private static final Namespace NAMESPACE = Namespace.of("contract");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    private ExternalLayerContract() {
        throw new UnsupportedOperationException("Utility class.");
    }

    public static void verifyConcurrentConditionalSave(@NotNull Layer layer) {
        Key<String> key = key("concurrent_conflict");
        Envelope first = envelope(key, "one", Map.of());

        Result<Void> firstSave = layer.save(first, context()).join();
        require(firstSave.successful(), "Expected first save to succeed, got " + firstSave.status() + " " + firstSave.failures());

        Envelope left = envelope(key, "left", Map.of());
        Envelope right = envelope(key, "right", Map.of());

        CompletableFuture<Result<Void>> leftSave = CompletableFuture.supplyAsync(() -> layer.save(left, context(SaveOptions.matching(first.stamp()))).join());
        CompletableFuture<Result<Void>> rightSave = CompletableFuture.supplyAsync(() -> layer.save(right, context(SaveOptions.matching(first.stamp()))).join());

        Result<Void> leftResult = leftSave.join();
        Result<Void> rightResult = rightSave.join();

        long successes = List.of(leftResult, rightResult).stream().filter(Result::successful).count();
        long conflicts = List.of(leftResult, rightResult).stream().filter(result -> result.status() == Status.CONFLICT).count();

        require(successes == 1L, "Expected exactly one concurrent save to succeed, got " + successes + ". Left=" + leftResult.status() + ", Right=" + rightResult.status());
        require(conflicts == 1L, "Expected exactly one concurrent save to conflict, got " + conflicts + ". Left=" + leftResult.status() + ", Right=" + rightResult.status());

        Result<Envelope> load = layer.load(key, context()).join();
        require(load.successful(), "Expected post-concurrency load to succeed, got " + load.status() + " " + load.failures());

        String value = value(load.valueOrThrow());
        require("left".equals(value) || "right".equals(value), "Expected final value to be left or right, got " + value);
    }

    public static void verifyKeyValue(@NotNull Layer layer) {
        Key<String> key = key("key_value");
        Envelope envelope = envelope(key, "hello", Map.of());

        Result<Void> save = layer.save(envelope, context()).join();
        require(save.successful(), "Expected save to succeed, got " + save.status() + " " + save.failures());

        Result<Envelope> load = layer.load(key, context()).join();
        require(load.successful(), "Expected load to succeed, got " + load.status() + " " + load.failures());
        require("hello".equals(value(load.valueOrThrow())), "Expected loaded value to be hello.");

        Result<Void> delete = layer.delete(key, context()).join();
        require(delete.successful(), "Expected delete to succeed, got " + delete.status() + " " + delete.failures());

        Result<Envelope> missing = layer.load(key, context()).join();
        require(missing.status() == Status.NOT_FOUND, "Expected load after delete to return NOT_FOUND, got " + missing.status());
    }

    public static void verifyTtl(@NotNull Layer layer) {
        require(layer.capabilities().ttl(), "Layer does not report TTL capability: " + layer.name());

        Key<String> key = key("ttl");
        Envelope envelope = envelope(key, "temporary", Map.of());

        Result<Void> save = layer.save(envelope, context(SaveOptions.defaults().withTtl(Duration.ofSeconds(1)))).join();
        require(save.successful(), "Expected TTL save to succeed, got " + save.status() + " " + save.failures());

        Result<Envelope> immediate = layer.load(key, context()).join();
        require(immediate.successful(), "Expected immediate TTL load to succeed, got " + immediate.status() + " " + immediate.failures());

        awaitNotFound(layer, key);
    }

    public static void verifyStampConflict(@NotNull Layer layer) {
        Key<String> key = key("conflict");
        Envelope first = envelope(key, "one", Map.of());

        Result<Void> firstSave = layer.save(first, context()).join();
        require(firstSave.successful(), "Expected first save to succeed, got " + firstSave.status() + " " + firstSave.failures());

        Envelope second = envelope(key, "two", Map.of());
        Result<Void> secondSave = layer.save(second, context(SaveOptions.matching(first.stamp()))).join();
        require(secondSave.successful(), "Expected matching-stamp save to succeed, got " + secondSave.status() + " " + secondSave.failures());

        Envelope stale = envelope(key, "stale", Map.of());
        Result<Void> staleSave = layer.save(stale, context(SaveOptions.matching(first.stamp()))).join();
        require(staleSave.status() == Status.CONFLICT, "Expected stale save to return CONFLICT, got " + staleSave.status() + " " + staleSave.failures());

        Result<Envelope> load = layer.load(key, context()).join();
        require(load.successful(), "Expected post-conflict load to succeed, got " + load.status() + " " + load.failures());
        require("two".equals(value(load.valueOrThrow())), "Expected value after conflict to remain two.");
    }

    public static void verifyIndexQuery(@NotNull Layer layer) {
        require(layer.capabilities().query(), "Layer does not report query capability: " + layer.name());
        require(layer.capabilities().index(), "Layer does not report index capability: " + layer.name());

        Key<String> redKey = key("indexed_red");
        Key<String> blueKey = key("indexed_blue");

        Result<Void> redSave = layer.save(envelope(redKey, "red-value", Map.of("group", "red")), context()).join();
        require(redSave.successful(), "Expected red save to succeed, got " + redSave.status() + " " + redSave.failures());

        Result<Void> blueSave = layer.save(envelope(blueKey, "blue-value", Map.of("group", "blue")), context()).join();
        require(blueSave.successful(), "Expected blue save to succeed, got " + blueSave.status() + " " + blueSave.failures());

        Result<List<Envelope>> query = layer.query(TYPE, Query.where("group").eq("red"), context()).join();
        require(query.successful(), "Expected index query to succeed, got " + query.status() + " " + query.failures());
        require(query.valueOrThrow().size() == 1, "Expected exactly one red result, got " + query.valueOrThrow().size());
        require("red-value".equals(value(query.valueOrThrow().getFirst())), "Expected red-value from index query.");

        Result<Void> delete = layer.delete(redKey, context()).join();
        require(delete.successful(), "Expected red delete to succeed, got " + delete.status() + " " + delete.failures());

        Result<List<Envelope>> afterDelete = layer.query(TYPE, Query.where("group").eq("red"), context()).join();
        require(afterDelete.successful(), "Expected index query after delete to succeed, got " + afterDelete.status() + " " + afterDelete.failures());
        require(afterDelete.valueOrThrow().isEmpty(), "Expected deleted indexed value to be removed from query results.");
    }

    public static void verifyQueryUnsupported(@NotNull Layer layer) {
        require(!layer.capabilities().query(), "Layer should not report query capability: " + layer.name());
        require(!layer.capabilities().index(), "Layer should not report index capability: " + layer.name());

        Result<List<Envelope>> query = layer.query(TYPE, Query.where("group").eq("red"), context()).join();
        require(query.status() == Status.UNSUPPORTED, "Expected unsupported query to return UNSUPPORTED, got " + query.status());
    }

    private static OperationContext context() {
        return context(SaveOptions.defaults());
    }

    private static OperationContext context(SaveOptions saveOptions) {
        return new OperationContext(DIRECT_EXECUTOR, saveOptions, LoadOptions.defaults(), DeleteOptions.defaults());
    }

    private static Key<String> key(String id) {
        return NAMESPACE.key(TYPE, "values", id + "_" + UUID.randomUUID());
    }

    private static Envelope envelope(Key<String> key, String value, Map<String, String> indexes) {
        Encoded encoded = Encoded.of(value.getBytes(StandardCharsets.UTF_8), "text/plain");
        Metadata metadata = Metadata.now().withIndexes(indexes);
        return new Envelope(key, encoded, Stamp.random(), metadata, false);
    }

    private static String value(Envelope envelope) {
        return new String(envelope.encoded().bytes(), StandardCharsets.UTF_8);
    }

    private static void awaitNotFound(Layer layer, Key<String> key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(6).toNanos();

        while (System.nanoTime() < deadline) {
            Result<Envelope> result = layer.load(key, context()).join();

            if (result.status() == Status.NOT_FOUND) {
                return;
            }

            sleep(Duration.ofMillis(250));
        }

        Result<Envelope> finalResult = layer.load(key, context()).join();
        require(finalResult.status() == Status.NOT_FOUND, "Expected TTL value to expire, got " + finalResult.status());
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for TTL expiry.", exception);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

}