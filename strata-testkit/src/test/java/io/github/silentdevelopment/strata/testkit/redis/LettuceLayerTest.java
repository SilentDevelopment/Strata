package io.github.silentdevelopment.strata.testkit.redis;


import io.github.silentdevelopment.strata.buffer.redis.lettuce.LettuceLayer;
import io.github.silentdevelopment.strata.testkit.ExternalLayerContract;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
final class LettuceLayerTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    @AfterAll
    static void tearDown() {
        if (connection != null) {
            connection.close();
        }

        if (client != null) {
            client.shutdown();
        }
    }

    @Test
    void supportsConcurrentConditionalSave() {
        ExternalLayerContract.verifyConcurrentConditionalSave(layer());
    }

    @Test
    void supportsKeyValueOperations() {
        ExternalLayerContract.verifyKeyValue(layer());
    }

    @Test
    void supportsTtl() {
        ExternalLayerContract.verifyTtl(layer());
    }

    @Test
    void supportsStampConflicts() {
        ExternalLayerContract.verifyStampConflict(layer());
    }

    @Test
    void supportsIndexQuery() {
        ExternalLayerContract.verifyIndexQuery(layer());
    }

    private static LettuceLayer layer() {
        return LettuceLayer.named("lettuce-test", connection(), "strata:test:lettuce");
    }

    private static StatefulRedisConnection<String, String> connection() {
        if (connection != null) {
            return connection;
        }

        client = RedisClient.create("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        connection = client.connect();
        return connection;
    }

}