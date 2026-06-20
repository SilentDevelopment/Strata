package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.buffer.memcached.MemcachedLayer;
import net.spy.memcached.MemcachedClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.net.InetSocketAddress;

@Testcontainers
final class MemcachedLayerTest {

    @Container
    private static final GenericContainer<?> MEMCACHED = new GenericContainer<>(DockerImageName.parse("memcached:1.6-alpine")).withExposedPorts(11211);

    private static MemcachedClient client;

    @AfterAll
    static void tearDown() {
        if (client == null) {
            return;
        }

        client.shutdown();
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
    void doesNotSupportQueryOrIndex() {
        ExternalLayerContract.verifyQueryUnsupported(layer());
    }

    private static MemcachedLayer layer() {
        return MemcachedLayer.named("memcached-test", client(), "strata:test:memcached");
    }

    private static MemcachedClient client() {
        if (client != null) {
            return client;
        }

        try {
            client = new MemcachedClient(new InetSocketAddress(MEMCACHED.getHost(), MEMCACHED.getMappedPort(11211)));
            return client;
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Memcached client.", exception);
        }
    }

}