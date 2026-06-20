package io.github.silentdevelopment.strata.testkit;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.durable.jdbc.mapped.MappedJdbcLayer;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;

final class MappedJdbcLayerTest {

    private static final Type<PlayerData> PLAYER_DATA = Type.of("player_data", PlayerData.class);
    private static final Namespace NAMESPACE = Namespace.of("mapped_test");
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final Codec<PlayerData> CODEC = new Codec<>() {
        @Override
        public Encoded encode(PlayerData value) {
            String serialized = value.name() + ";" + value.coins() + ";" + value.guild() + ";" + value.level();
            return Encoded.of(serialized.getBytes(StandardCharsets.UTF_8), "text/plain");
        }

        @Override
        public PlayerData decode(Encoded encoded, Type<PlayerData> type) {
            String[] parts = new String(encoded.bytes(), StandardCharsets.UTF_8).split(";", 4);
            return new PlayerData(parts[0], Integer.parseInt(parts[1]), parts[2], Integer.parseInt(parts[3]));
        }
    };

    @Test
    void supportsMappedColumnSorting() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();

        assertThat(layer.save(envelope(key("low"), new PlayerData("Low", 10, "red", 1)), context()).join().successful()).isTrue();
        assertThat(layer.save(envelope(key("mid"), new PlayerData("Mid", 50, "red", 5)), context()).join().successful()).isTrue();
        assertThat(layer.save(envelope(key("high"), new PlayerData("High", 100, "red", 10)), context()).join().successful()).isTrue();

        Result<List<Envelope>> result = layer.query(PLAYER_DATA, Query.where("guild").eq("red").sortDesc("coins").limit(2), context()).join();

        assertThat(result.successful()).isTrue();
        assertThat(result.valueOrThrow()).hasSize(2);
        assertThat(decode(result.valueOrThrow().get(0))).isEqualTo(new PlayerData("High", 100, "red", 10));
        assertThat(decode(result.valueOrThrow().get(1))).isEqualTo(new PlayerData("Mid", 50, "red", 5));
    }

    @Test
    void supportsKeyValueOperations() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();
        Key<PlayerData> key = key("key_value");
        PlayerData value = new PlayerData("Silent", 100, "red", 10);

        Result<Void> save = layer.save(envelope(key, value), context()).join();

        assertThat(save.successful()).isTrue();

        Result<Envelope> load = layer.load(key, context()).join();

        assertThat(load.successful()).isTrue();
        assertThat(decode(load.valueOrThrow())).isEqualTo(value);

        Result<Void> delete = layer.delete(key, context()).join();

        assertThat(delete.successful()).isTrue();
        assertThat(layer.load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void supportsTtl() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();
        Key<PlayerData> key = key("ttl");

        Result<Void> save = layer.save(envelope(key, new PlayerData("Temp", 1, "red", 1)), context(SaveOptions.defaults().withTtl(Duration.ofSeconds(1)))).join();

        assertThat(save.successful()).isTrue();
        assertThat(layer.load(key, context()).join().successful()).isTrue();

        awaitNotFound(layer, key);
    }

    @Test
    void supportsStampConflicts() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();
        Key<PlayerData> key = key("conflict");
        Envelope first = envelope(key, new PlayerData("One", 1, "red", 1));

        assertThat(layer.save(first, context()).join().successful()).isTrue();

        Envelope second = envelope(key, new PlayerData("Two", 2, "red", 2));
        Result<Void> secondSave = layer.save(second, context(SaveOptions.matching(first.stamp()))).join();

        assertThat(secondSave.successful()).isTrue();

        Envelope stale = envelope(key, new PlayerData("Stale", 3, "red", 3));
        Result<Void> staleSave = layer.save(stale, context(SaveOptions.matching(first.stamp()))).join();

        assertThat(staleSave.status()).isEqualTo(Status.CONFLICT);
        assertThat(decode(layer.load(key, context()).join().valueOrThrow())).isEqualTo(new PlayerData("Two", 2, "red", 2));
    }

    @Test
    void supportsMappedColumnQueries() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();

        Key<PlayerData> red = key("red");
        Key<PlayerData> blue = key("blue");
        Key<PlayerData> rich = key("rich");

        assertThat(layer.save(envelope(red, new PlayerData("Red", 100, "red", 10)), context()).join().successful()).isTrue();
        assertThat(layer.save(envelope(blue, new PlayerData("Blue", 50, "blue", 5)), context()).join().successful()).isTrue();
        assertThat(layer.save(envelope(rich, new PlayerData("Rich", 1000, "red", 99)), context()).join().successful()).isTrue();

        Result<List<Envelope>> redQuery = layer.query(PLAYER_DATA, Query.where("guild").eq("red"), context()).join();

        assertThat(redQuery.successful()).isTrue();
        assertThat(redQuery.valueOrThrow()).hasSize(2);

        Result<List<Envelope>> coinQuery = layer.query(PLAYER_DATA, Query.where("coins").gt(100), context()).join();

        assertThat(coinQuery.successful()).isTrue();
        assertThat(coinQuery.valueOrThrow()).hasSize(1);
        assertThat(decode(coinQuery.valueOrThrow().getFirst())).isEqualTo(new PlayerData("Rich", 1000, "red", 99));
    }

    @Test
    void supportsConcurrentConditionalSave() throws Exception {
        MappedJdbcLayer<PlayerData> layer = layer();
        Key<PlayerData> key = key("concurrent");
        Envelope first = envelope(key, new PlayerData("First", 1, "red", 1));

        assertThat(layer.save(first, context()).join().successful()).isTrue();

        Envelope left = envelope(key, new PlayerData("Left", 2, "red", 2));
        Envelope right = envelope(key, new PlayerData("Right", 3, "red", 3));

        CompletableFuture<Result<Void>> leftSave = CompletableFuture.supplyAsync(() -> layer.save(left, context(SaveOptions.matching(first.stamp()))).join());
        CompletableFuture<Result<Void>> rightSave = CompletableFuture.supplyAsync(() -> layer.save(right, context(SaveOptions.matching(first.stamp()))).join());

        Result<Void> leftResult = leftSave.join();
        Result<Void> rightResult = rightSave.join();

        long successes = List.of(leftResult, rightResult).stream().filter(Result::successful).count();
        long conflicts = List.of(leftResult, rightResult).stream().filter(result -> result.status() == Status.CONFLICT).count();

        assertThat(successes).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
    }

    private static MappedJdbcLayer<PlayerData> layer() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mapped_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        MappedJdbcLayer<PlayerData> layer = MappedJdbcLayer.forType(PLAYER_DATA)
                .named("mapped-player-jdbc")
                .table("player_data")
                .codec(CODEC)
                .indexedStringColumn("name", PlayerData::name, 64)
                .indexedIntColumn("coins", PlayerData::coins)
                .indexedStringColumn("guild", PlayerData::guild, 64)
                .indexedIntColumn("level", PlayerData::level)
                .mapper(row -> new PlayerData(row.string("name"), row.integer("coins"), row.string("guild"), row.integer("level")))
                .build(dataSource);

        layer.createSchema();
        return layer;
    }

    private static Key<PlayerData> key(String id) {
        return NAMESPACE.key(PLAYER_DATA, "players", id + "_" + UUID.randomUUID());
    }

    private static Envelope envelope(Key<PlayerData> key, PlayerData value) {
        Map<String, String> indexes = Map.of(
                "name", value.name(),
                "coins", String.valueOf(value.coins()),
                "guild", value.guild(),
                "level", String.valueOf(value.level())
        );

        return new Envelope(key, CODEC.encode(value), Stamp.random(), Metadata.now().withIndexes(indexes), false);
    }

    private static OperationContext context() {
        return context(SaveOptions.defaults());
    }

    private static OperationContext context(SaveOptions options) {
        return new OperationContext(DIRECT_EXECUTOR, options, LoadOptions.defaults(), DeleteOptions.defaults());
    }

    private static PlayerData decode(Envelope envelope) {
        return CODEC.decode(envelope.encoded(), PLAYER_DATA);
    }

    private static void awaitNotFound(MappedJdbcLayer<PlayerData> layer, Key<PlayerData> key) {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();

        while (System.nanoTime() < deadline) {
            Result<Envelope> result = layer.load(key, context()).join();

            if (result.status() == Status.NOT_FOUND) {
                return;
            }

            try {
                Thread.sleep(250L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for TTL expiry.", exception);
            }
        }

        assertThat(layer.load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
    }

    public record PlayerData(String name, int coins, String guild, int level) { }

}