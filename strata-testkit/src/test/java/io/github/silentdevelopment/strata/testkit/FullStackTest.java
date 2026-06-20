package io.github.silentdevelopment.strata.testkit;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Strata;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.buffer.redis.redisson.RedissonLayer;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.codec.gson.GsonCodec;
import io.github.silentdevelopment.strata.durable.mongodb.MongoLayer;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.InsertOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.ReadPolicy;
import io.github.silentdevelopment.strata.operation.ReadRepair;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import io.github.silentdevelopment.strata.soft.memory.MemoryLayer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
final class FullStackTest {

    private static final Type<PlayerData> PLAYER_DATA = Type.of("player_data", PlayerData.class);
    private static final GsonCodec<PlayerData> CODEC = GsonCodec.of(PlayerData.class);
    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:8.0")).withExposedPorts(27017);

    private static RedissonClient redisson;
    private static MongoClient mongo;
    private static MongoDatabase database;

    @AfterAll
    static void tearDown() {
        if (database != null) {
            database.drop();
        }

        if (mongo != null) {
            mongo.close();
        }

        if (redisson != null) {
            redisson.shutdown();
        }
    }

    @Test
    void fullStackSavesLoadsQueriesMutatesInsertsAndDeletes() {
        StackFixture fixture = fixture("full_flow");
        Key<PlayerData> key = fixture.key("players", "silent");

        Result<Void> save = fixture.stack().save(key, new PlayerData("Silent", 100, "red"), SaveOptions.defaults().withIndex("guild", "red")).join();

        assertThat(save.successful()).isTrue();
        assertThat(save.hasWarnings()).isFalse();

        Result<Entry<PlayerData>> load = fixture.stack().load(key).join();

        assertThat(load.successful()).isTrue();
        assertThat(load.valueOrThrow().value()).isEqualTo(new PlayerData("Silent", 100, "red"));

        Result<Entry<PlayerData>> mutate = fixture.stack().mutate(key, data -> new PlayerData(data.name(), data.coins() + 50, data.guild())).join();

        assertThat(mutate.successful()).isTrue();
        assertThat(mutate.valueOrThrow().value()).isEqualTo(new PlayerData("Silent", 150, "red"));

        Result<List<Entry<PlayerData>>> query = fixture.stack().findByIndex(PLAYER_DATA, "guild", "red").join();

        assertThat(query.successful()).isTrue();
        assertThat(query.valueOrThrow()).hasSize(1);
        assertThat(query.valueOrThrow().getFirst().value()).isEqualTo(new PlayerData("Silent", 150, "red"));

        Result<Key<PlayerData>> inserted = fixture.stack().insert(PLAYER_DATA, new PlayerData("Generated", 10, "blue"), InsertOptions.of(fixture.namespace(), "generated_players")).join();

        assertThat(inserted.successful()).isTrue();
        assertThat(inserted.valueOrThrow().external()).contains("generated_players");

        Result<Entry<PlayerData>> generated = fixture.stack().load(inserted.valueOrThrow()).join();

        assertThat(generated.successful()).isTrue();
        assertThat(generated.valueOrThrow().value()).isEqualTo(new PlayerData("Generated", 10, "blue"));

        Result<Void> delete = fixture.stack().delete(key).join();

        assertThat(delete.successful()).isTrue();

        Result<Entry<PlayerData>> deleted = fixture.stack().load(key).join();

        assertThat(deleted.status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void durableReadRepairsSoftAndBufferLayers() {
        StackFixture fixture = fixture("read_repair");
        Key<PlayerData> key = fixture.key("players", "repair");
        Envelope durableEnvelope = envelope(key, new PlayerData("Durable", 200, "green"), Map.of("guild", "green"));

        Result<Void> durableSave = fixture.durable().save(durableEnvelope, context()).join();

        assertThat(durableSave.successful()).isTrue();
        assertThat(fixture.soft().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(fixture.buffer().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);

        Result<Entry<PlayerData>> loaded = fixture.stack().load(key).join();

        assertThat(loaded.successful()).isTrue();
        assertThat(loaded.valueOrThrow().value()).isEqualTo(new PlayerData("Durable", 200, "green"));

        Result<Envelope> softAfterRepair = fixture.soft().load(key, context()).join();
        Result<Envelope> bufferAfterRepair = fixture.buffer().load(key, context()).join();

        assertThat(softAfterRepair.successful()).isTrue();
        assertThat(bufferAfterRepair.successful()).isTrue();
        assertThat(decode(softAfterRepair.valueOrThrow())).isEqualTo(new PlayerData("Durable", 200, "green"));
        assertThat(decode(bufferAfterRepair.valueOrThrow())).isEqualTo(new PlayerData("Durable", 200, "green"));
    }

    @Test
    void defaultReadUsesDurableInsteadOfStaleSoftAndBuffer() {
        StackFixture fixture = fixture("durable_authority");
        Key<PlayerData> key = fixture.key("players", "authority");

        Envelope stale = envelope(key, new PlayerData("Stale", 1, "old"), Map.of("guild", "old"));
        Envelope current = envelope(key, new PlayerData("Current", 999, "new"), Map.of("guild", "new"));

        assertThat(fixture.soft().save(stale, context()).join().successful()).isTrue();
        assertThat(fixture.buffer().save(stale, context()).join().successful()).isTrue();
        assertThat(fixture.durable().save(current, context()).join().successful()).isTrue();

        Result<Entry<PlayerData>> firstAvailableBeforeRepair = fixture.stack().load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstAvailableBeforeRepair.successful()).isTrue();
        assertThat(firstAvailableBeforeRepair.valueOrThrow().value()).isEqualTo(new PlayerData("Stale", 1, "old"));

        Result<Entry<PlayerData>> defaultLoad = fixture.stack().load(key).join();

        assertThat(defaultLoad.successful()).isTrue();
        assertThat(defaultLoad.valueOrThrow().value()).isEqualTo(new PlayerData("Current", 999, "new"));

        Result<Entry<PlayerData>> firstAvailableAfterRepair = fixture.stack().load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstAvailableAfterRepair.successful()).isTrue();
        assertThat(firstAvailableAfterRepair.valueOrThrow().value()).isEqualTo(new PlayerData("Current", 999, "new"));
    }

    @Test
    void deleteInvalidatesSoftBufferAndDurableLayers() {
        StackFixture fixture = fixture("delete_invalidate");
        Key<PlayerData> key = fixture.key("players", "delete");
        PlayerData value = new PlayerData("Delete", 5, "red");

        Result<Void> save = fixture.stack().save(key, value, SaveOptions.defaults().withIndex("guild", "red")).join();

        assertThat(save.successful()).isTrue();
        assertThat(fixture.soft().load(key, context()).join().successful()).isTrue();
        assertThat(fixture.buffer().load(key, context()).join().successful()).isTrue();
        assertThat(fixture.durable().load(key, context()).join().successful()).isTrue();

        Result<Void> delete = fixture.stack().delete(key).join();

        assertThat(delete.successful()).isTrue();
        assertThat(fixture.soft().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(fixture.buffer().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(fixture.durable().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
    }

    @Test
    void durableQueryIsAuthoritative() {
        StackFixture fixture = fixture("query_authority");
        Key<PlayerData> key = fixture.key("players", "query_authority");

        Envelope bufferOnly = envelope(key, new PlayerData("Buffer", 1, "red"), Map.of("guild", "red"));
        Envelope durableOnly = envelope(key, new PlayerData("Durable", 2, "blue"), Map.of("guild", "blue"));

        assertThat(fixture.buffer().save(bufferOnly, context()).join().successful()).isTrue();
        assertThat(fixture.durable().save(durableOnly, context()).join().successful()).isTrue();

        Result<List<Entry<PlayerData>>> red = fixture.stack().findByIndex(PLAYER_DATA, "guild", "red").join();
        Result<List<Entry<PlayerData>>> blue = fixture.stack().findByIndex(PLAYER_DATA, "guild", "blue").join();

        assertThat(red.successful()).isTrue();
        assertThat(red.valueOrThrow()).isEmpty();

        assertThat(blue.successful()).isTrue();
        assertThat(blue.valueOrThrow()).hasSize(1);
        assertThat(blue.valueOrThrow().getFirst().value()).isEqualTo(new PlayerData("Durable", 2, "blue"));
    }

    private static StackFixture fixture(String name) {
        Namespace namespace = Namespace.of("stack_test_" + name + "_" + UUID.randomUUID().toString().replace("-", ""));
        MemoryLayer soft = MemoryLayer.named("memory-" + name);
        RedissonLayer buffer = RedissonLayer.named("redisson-" + name, redisson(), "strata:test:full-stack:" + name + ":" + UUID.randomUUID().toString().replace("-", ""));
        MongoLayer durable = MongoLayer.named("mongo-" + name, database(), "stack_entries");

        Stack stack = Strata.stack()
                .soft(soft)
                .buffer(buffer)
                .durable(durable)
                .codec(PLAYER_DATA, CODEC)
                .build();

        return new StackFixture(namespace, stack, soft, buffer, durable);
    }

    private static RedissonClient redisson() {
        if (redisson != null) {
            return redisson;
        }

        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);
        return redisson;
    }

    private static MongoDatabase database() {
        if (database != null) {
            return database;
        }

        String uri = "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017);
        mongo = MongoClients.create(uri);
        database = mongo.getDatabase("strata_stack_test_" + UUID.randomUUID().toString().replace("-", ""));
        return database;
    }

    private static OperationContext context() {
        return new OperationContext(DIRECT_EXECUTOR, SaveOptions.defaults(), LoadOptions.defaults(), DeleteOptions.defaults());
    }

    private static Envelope envelope(Key<PlayerData> key, PlayerData value, Map<String, String> indexes) {
        Encoded encoded = CODEC.encode(value);
        Metadata metadata = Metadata.now().withIndexes(indexes);
        return new Envelope(key, encoded, Stamp.random(), metadata, false);
    }

    private static PlayerData decode(Envelope envelope) {
        return CODEC.decode(envelope.encoded(), PLAYER_DATA);
    }

    private record StackFixture(Namespace namespace, Stack stack, MemoryLayer soft, RedissonLayer buffer, MongoLayer durable) {

        private Key<PlayerData> key(String collection, String id) {
            return namespace.key(PLAYER_DATA, collection, id);
        }

    }

    public record PlayerData(String name, int coins, String guild) {
    }

}