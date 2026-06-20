package io.github.silentdevelopment.strata.testkit.redis;


import io.github.silentdevelopment.strata.buffer.redis.redisson.RedissonLayer;
import io.github.silentdevelopment.strata.testkit.ExternalLayerContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
final class RedissonLayerTest {

    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    private static RedissonClient redisson;

    @AfterAll
    static void tearDown() {
        if (redisson != null) {
            redisson.shutdown();
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

    private static RedissonLayer layer() {
        return RedissonLayer.named("redisson-test", client(), "strata:test:redisson");
    }

    private static RedissonClient client() {
        if (redisson != null) {
            return redisson;
        }

        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redisson = Redisson.create(config);
        return redisson;
    }

}