package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.durable.mongodb.MongoLayer;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

@Testcontainers
final class MongoLayerTest {

    @Container
    private static final GenericContainer<?> MONGO = new GenericContainer<>(DockerImageName.parse("mongo:8.0")).withExposedPorts(27017);

    private static MongoClient client;
    private static MongoDatabase database;

    @AfterAll
    static void tearDown() {
        if (database != null) {
            database.drop();
        }

        if (client != null) {
            client.close();
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

    private static MongoLayer layer() {
        return MongoLayer.named("mongo-test", database(), "strata_entries");
    }

    private static MongoDatabase database() {
        if (database != null) {
            return database;
        }

        String uri = "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017);
        client = MongoClients.create(uri);
        database = client.getDatabase("strata_test_" + UUID.randomUUID().toString().replace("-", ""));
        return database;
    }

}