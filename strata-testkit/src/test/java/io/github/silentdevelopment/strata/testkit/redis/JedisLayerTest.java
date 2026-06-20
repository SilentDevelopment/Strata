package io.github.silentdevelopment.strata.testkit.redis;


import io.github.silentdevelopment.strata.buffer.redis.jedis.JedisLayer;
import io.github.silentdevelopment.strata.testkit.ExternalLayerContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.JedisPooled;

@Testcontainers
final class JedisLayerTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static JedisPooled jedis;

    @AfterAll
    static void tearDown() {
        if (jedis == null) {
            return;
        }

        jedis.close();
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

    private static JedisLayer layer() {
        return JedisLayer.named("jedis-test", jedis(), "strata:test:jedis");
    }

    private static JedisPooled jedis() {
        if (jedis != null) {
            return jedis;
        }

        jedis = new JedisPooled(REDIS.getHost(), REDIS.getMappedPort(6379));
        return jedis;
    }

}