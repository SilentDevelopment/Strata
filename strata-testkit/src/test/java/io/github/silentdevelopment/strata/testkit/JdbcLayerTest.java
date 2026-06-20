package io.github.silentdevelopment.strata.testkit;


import io.github.silentdevelopment.strata.durable.jdbc.JdbcDialect;
import io.github.silentdevelopment.strata.durable.jdbc.JdbcLayer;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.UUID;

final class JdbcLayerTest {

    @Test
    void supportsKeyValueOperations() throws SQLException {
        ExternalLayerContract.verifyKeyValue(layer());
    }

    @Test
    void supportsTtl() throws SQLException {
        ExternalLayerContract.verifyTtl(layer());
    }

    @Test
    void supportsStampConflicts() throws SQLException {
        ExternalLayerContract.verifyStampConflict(layer());
    }

    @Test
    void supportsIndexQuery() throws SQLException {
        ExternalLayerContract.verifyIndexQuery(layer());
    }

    @Test
    void supportsConcurrentConditionalSave() throws SQLException {
        ExternalLayerContract.verifyConcurrentConditionalSave(layer());
    }

    private static JdbcLayer layer() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:strata_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");

        JdbcLayer layer = JdbcLayer.named("jdbc-test", dataSource, "strata_entries", JdbcDialect.H2);
        layer.createSchema();
        return layer;
    }

}