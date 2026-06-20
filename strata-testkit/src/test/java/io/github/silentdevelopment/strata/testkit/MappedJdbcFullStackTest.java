package io.github.silentdevelopment.strata.testkit;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Stack;
import io.github.silentdevelopment.strata.Strata;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.buffer.redis.redisson.RedissonLayer;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.durable.jdbc.mapped.MappedJdbcLayer;
import io.github.silentdevelopment.strata.entry.Entry;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.operation.DeleteOptions;
import io.github.silentdevelopment.strata.operation.LoadOptions;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.ReadPolicy;
import io.github.silentdevelopment.strata.operation.ReadRepair;
import io.github.silentdevelopment.strata.operation.SaveOptions;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import io.github.silentdevelopment.strata.soft.memory.MemoryLayer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
final class MappedJdbcFullStackTest {

    private static final Type<PlayerData> PLAYER_DATA = Type.of("player_data", PlayerData.class);
    private static final Namespace NAMESPACE = Namespace.of("mapped_stack");
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

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static RedissonClient redisson;

    @AfterAll
    static void tearDown() {
        if (redisson == null) {
            return;
        }

        redisson.shutdown();
    }

    @Test
    void stackUsesMappedJdbcAsDurableAuthority() throws Exception {
        Fixture fixture = fixture("authority");
        Key<PlayerData> key = key("authority");

        Result<Void> save = fixture.stack().save(key, new PlayerData("Silent", 100, "red", 10), SaveOptions.defaults().withIndex("guild", "red").withIndex("coins", "100")).join();

        assertThat(save.successful()).isTrue();

        Result<Entry<PlayerData>> load = fixture.stack().load(key).join();

        assertThat(load.successful()).isTrue();
        assertThat(load.valueOrThrow().value()).isEqualTo(new PlayerData("Silent", 100, "red", 10));

        Result<Entry<PlayerData>> mutate = fixture.stack().mutate(key, current -> new PlayerData(current.name(), current.coins() + 50, current.guild(), current.level() + 1)).join();

        assertThat(mutate.successful()).isTrue();
        assertThat(mutate.valueOrThrow().value()).isEqualTo(new PlayerData("Silent", 150, "red", 11));
    }

    @Test
    void stackQueriesMappedJdbcColumnsWithSorting() throws Exception {
        Fixture fixture = fixture("query_sort");

        fixture.stack().save(key("low"), new PlayerData("Low", 10, "red", 1), SaveOptions.defaults().withIndex("guild", "red").withIndex("coins", "10")).join();
        fixture.stack().save(key("mid"), new PlayerData("Mid", 50, "red", 5), SaveOptions.defaults().withIndex("guild", "red").withIndex("coins", "50")).join();
        fixture.stack().save(key("high"), new PlayerData("High", 100, "red", 10), SaveOptions.defaults().withIndex("guild", "red").withIndex("coins", "100")).join();

        Result<List<Entry<PlayerData>>> result = fixture.stack().query(PLAYER_DATA, Query.where("guild").eq("red").sortDesc("coins").limit(2)).join();

        assertThat(result.successful()).isTrue();
        assertThat(result.valueOrThrow()).hasSize(2);
        assertThat(result.valueOrThrow().get(0).value()).isEqualTo(new PlayerData("High", 100, "red", 10));
        assertThat(result.valueOrThrow().get(1).value()).isEqualTo(new PlayerData("Mid", 50, "red", 5));
    }

    @Test
    void durableLoadRepairsMemoryAndRedis() throws Exception {
        Fixture fixture = fixture("repair");
        Key<PlayerData> key = key("repair");

        Result<Void> durableSave = fixture.sql().save(envelope(key, new PlayerData("Durable", 999, "blue", 99)), context()).join();

        assertThat(durableSave.successful()).isTrue();
        assertThat(fixture.memory().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);
        assertThat(fixture.redis().load(key, context()).join().status()).isEqualTo(Status.NOT_FOUND);

        Result<Entry<PlayerData>> load = fixture.stack().load(key).join();

        assertThat(load.successful()).isTrue();
        assertThat(load.valueOrThrow().value()).isEqualTo(new PlayerData("Durable", 999, "blue", 99));

        Result<Envelope> memory = fixture.memory().load(key, context()).join();
        Result<Envelope> redis = fixture.redis().load(key, context()).join();

        assertThat(memory.successful()).isTrue();
        assertThat(redis.successful()).isTrue();
    }

    @Test
    void firstAvailableCanReturnStaleBeforeRepairAndCurrentAfterRepair() throws Exception {
        Fixture fixture = fixture("stale");
        Key<PlayerData> key = key("stale");

        fixture.memory().save(envelope(key, new PlayerData("Stale", 1, "old", 1)), context()).join();
        fixture.redis().save(envelope(key, new PlayerData("Stale", 1, "old", 1)), context()).join();
        fixture.sql().save(envelope(key, new PlayerData("Current", 999, "new", 99)), context()).join();

        Result<Entry<PlayerData>> firstBefore = fixture.stack().load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstBefore.successful()).isTrue();
        assertThat(firstBefore.valueOrThrow().value()).isEqualTo(new PlayerData("Stale", 1, "old", 1));

        Result<Entry<PlayerData>> durable = fixture.stack().load(key).join();

        assertThat(durable.successful()).isTrue();
        assertThat(durable.valueOrThrow().value()).isEqualTo(new PlayerData("Current", 999, "new", 99));

        Result<Entry<PlayerData>> firstAfter = fixture.stack().load(key, new LoadOptions(ReadPolicy.FIRST_AVAILABLE, ReadRepair.DISABLED)).join();

        assertThat(firstAfter.successful()).isTrue();
        assertThat(firstAfter.valueOrThrow().value()).isEqualTo(new PlayerData("Current", 999, "new", 99));
    }

    private static Fixture fixture(String name) throws Exception {
        MemoryLayer memory = MemoryLayer.named("memory-" + name);
        RedissonLayer redis = RedissonLayer.named("redis-" + name, redisson(), "strata:test:mapped:" + name + ":" + UUID.randomUUID().toString().replace("-", ""));
        MappedJdbcLayer<PlayerData> sql = mappedLayer(name);

        Stack stack = Strata.stack()
                .soft(memory)
                .buffer(redis)
                .durable(sql)
                .codec(PLAYER_DATA, CODEC)
                .build();

        return new Fixture(stack, memory, redis, sql);
    }

    private static MappedJdbcLayer<PlayerData> mappedLayer(String name) throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mapped_stack_" + name + "_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        MappedJdbcLayer<PlayerData> layer = MappedJdbcLayer.forType(PLAYER_DATA)
                .named("mapped-jdbc-" + name)
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
        return new Envelope(key, CODEC.encode(value), Stamp.random(), Metadata.now().withIndexes(Map.of(
                "name", value.name(),
                "coins", String.valueOf(value.coins()),
                "guild", value.guild(),
                "level", String.valueOf(value.level())
        )), false);
    }

    private static OperationContext context() {
        return new OperationContext(DIRECT_EXECUTOR, SaveOptions.defaults(), LoadOptions.defaults(), DeleteOptions.defaults());
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

    private record Fixture(Stack stack, MemoryLayer memory, RedissonLayer redis, MappedJdbcLayer<PlayerData> sql) {
    }

    public record PlayerData(String name, int coins, String guild, int level) {
    }

}