package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.lock.LockHandle;
import io.github.silentdevelopment.strata.lock.redis.redisson.RedissonLockManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
final class RedissonLockManagerTest {

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
    void acquiresAndReleasesLock() {
        RedissonLockManager manager = RedissonLockManager.named(client(), "strata:test:lock");
        LockHandle handle = manager.acquire("sample", Duration.ofSeconds(2), Duration.ofSeconds(5)).join();

        RLock rawLock = client().getLock("strata:test:lock:sample");
        boolean acquiredWhileHeld = CompletableFuture.supplyAsync(() -> tryLock(rawLock, 100, 100)).join();

        assertThat(acquiredWhileHeld).isFalse();

        handle.close();

        boolean acquiredAfterClose = tryLock(rawLock, 2_000, 1_000);

        if (acquiredAfterClose) {
            rawLock.unlock();
        }

        assertThat(acquiredAfterClose).isTrue();
    }

    private static boolean tryLock(RLock lock, long waitMillis, long leaseMillis) {
        try {
            return lock.tryLock(waitMillis, leaseMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while trying to lock.", exception);
        }
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