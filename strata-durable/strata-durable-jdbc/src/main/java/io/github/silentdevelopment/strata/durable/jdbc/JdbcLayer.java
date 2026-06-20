package io.github.silentdevelopment.strata.durable.jdbc;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.entry.DefaultEnvelopeCodec;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Generic JDBC DURABLE layer for encoded envelope storage.
 *
 * <p>The dialect controls binary column type and schema creation behavior.</p>
 */
public final class JdbcLayer implements Layer {

    private final String name;
    private final DataSource dataSource;
    private final String table;
    private final JdbcDialect dialect;

    private JdbcLayer(@NotNull String name, @NotNull DataSource dataSource, @NotNull String table, @NotNull JdbcDialect dialect) {
        this.name = Objects.requireNonNull(name, "name");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = Objects.requireNonNull(table, "table");
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    public static JdbcLayer create(@NotNull DataSource dataSource) {
        return new JdbcLayer("jdbc", dataSource, "strata_entries", JdbcDialect.GENERIC);
    }

    public static JdbcLayer named(@NotNull String name, @NotNull DataSource dataSource, @NotNull String table) {
        return new JdbcLayer(name, dataSource, table, JdbcDialect.GENERIC);
    }

    public static JdbcLayer named(@NotNull String name, @NotNull DataSource dataSource, @NotNull String table, @NotNull JdbcDialect dialect) {
        return new JdbcLayer(name, dataSource, table, dialect);
    }

    public void createSchema() throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + table + " (namespace VARCHAR(255) NOT NULL, type_id VARCHAR(255) NOT NULL, path VARCHAR(1024) NOT NULL, stamp VARCHAR(255) NOT NULL, payload " + dialect.binaryType() + " NOT NULL, updated_at BIGINT NOT NULL, expires_at BIGINT NULL, PRIMARY KEY(namespace, type_id, path))");
        }
    }

    @Override
    public @NotNull String name() {
        return name;
    }

    @Override
    public @NotNull Role role() {
        return Role.DURABLE;
    }

    @Override
    public @NotNull Capabilities capabilities() {
        return Capabilities.keyValue(Role.DURABLE).withQuery(true).withIndex(true).withTtl(true);
    }

    @Override
    public @NotNull CompletableFuture<Result<Envelope>> load(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> loadNow(key), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> save(@NotNull Envelope envelope, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> saveNow(envelope, context), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<Void>> delete(@NotNull Key<?> key, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> deleteNow(key), context.executor());
    }

    @Override
    public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> type, @NotNull Query query, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> queryNow(type, query), context.executor());
    }

    private Result<Envelope> loadNow(Key<?> key) {
        Instant start = Instant.now();

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT payload FROM " + table + " WHERE namespace = ? AND type_id = ? AND path = ?")) {
            statement.setString(1, key.namespace().value());
            statement.setString(2, key.type().id());
            statement.setString(3, key.path());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
                }

                Envelope envelope = deserialize(resultSet.getBytes(1));

                if (envelope.metadata().expired()) {
                    deleteNow(key);
                    return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
                }

                return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
            }
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();

        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);

            try {
                Envelope stored = applyTtl(envelope, context.saveOptions().ttl());
                SaveCondition condition = context.saveOptions().condition();
                byte[] bytes = serialize(stored);
                Long expiresAt = stored.metadata().expiresAt() == null ? null : stored.metadata().expiresAt().toEpochMilli();

                Result<Void> result;

                if (condition.expectedStamp() != null) {
                    result = conditionalUpdate(connection, stored, bytes, expiresAt, condition, start);
                } else {
                    result = upsert(connection, stored, bytes, expiresAt, start);
                }

                if (!result.successful()) {
                    connection.rollback();
                    return result;
                }

                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<Void> conditionalUpdate(Connection connection, Envelope envelope, byte[] bytes, Long expiresAt, SaveCondition condition, Instant start) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET stamp = ?, payload = ?, updated_at = ?, expires_at = ? WHERE namespace = ? AND type_id = ? AND path = ? AND stamp = ?")) {
            update.setString(1, envelope.stamp().value());
            update.setBytes(2, bytes);
            update.setLong(3, Instant.now().toEpochMilli());
            setNullableLong(update, 4, expiresAt);
            update.setString(5, envelope.key().namespace().value());
            update.setString(6, envelope.key().type().id());
            update.setString(7, envelope.key().path());
            update.setString(8, condition.expectedStamp().value());

            int updated = update.executeUpdate();

            if (updated == 0) {
                Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        }
    }

    private Result<Void> upsert(Connection connection, Envelope envelope, byte[] bytes, Long expiresAt, Instant start) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement("UPDATE " + table + " SET stamp = ?, payload = ?, updated_at = ?, expires_at = ? WHERE namespace = ? AND type_id = ? AND path = ?")) {
            update.setString(1, envelope.stamp().value());
            update.setBytes(2, bytes);
            update.setLong(3, Instant.now().toEpochMilli());
            setNullableLong(update, 4, expiresAt);
            update.setString(5, envelope.key().namespace().value());
            update.setString(6, envelope.key().type().id());
            update.setString(7, envelope.key().path());

            int updated = update.executeUpdate();

            if (updated == 0) {
                insert(connection, envelope, bytes, expiresAt);
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE namespace = ? AND type_id = ? AND path = ?")) {
            statement.setString(1, key.namespace().value());
            statement.setString(2, key.type().id());
            statement.setString(3, key.path());
            statement.executeUpdate();

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> type, Query query) {
        Instant start = Instant.now();
        List<Envelope> values = new ArrayList<>();

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("SELECT payload FROM " + table + " WHERE type_id = ?")) {
            statement.setString(1, type.id());

            int skipped = 0;

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Envelope envelope = deserialize(resultSet.getBytes(1));

                    if (!envelope.key().type().id().equals(type.id())) {
                        continue;
                    }

                    if (envelope.metadata().expired()) {
                        deleteNow(envelope.key());
                        continue;
                    }

                    if (!query.matches(envelope)) {
                        continue;
                    }

                    if (skipped < query.offset()) {
                        skipped++;
                        continue;
                    }

                    values.add(envelope);

                    if (query.limit() > 0 && values.size() >= query.limit()) {
                        break;
                    }
                }
            }

            return Result.success(values, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            Failure failure = Failure.of(name, role(), exception);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }
    }

    private void insert(Connection connection, Envelope envelope, byte[] payload, Long expiresAt) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " (namespace, type_id, path, stamp, payload, updated_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, envelope.key().namespace().value());
            insert.setString(2, envelope.key().type().id());
            insert.setString(3, envelope.key().path());
            insert.setString(4, envelope.stamp().value());
            insert.setBytes(5, payload);
            insert.setLong(6, Instant.now().toEpochMilli());
            setNullableLong(insert, 7, expiresAt);
            insert.executeUpdate();
        }
    }

    private Envelope applyTtl(Envelope envelope, Duration ttl) {
        if (ttl == null) {
            return envelope;
        }

        return envelope.withMetadata(envelope.metadata().withExpiresAt(Instant.now().plus(ttl)));
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }

        statement.setLong(index, value);
    }

    private static byte[] serialize(Envelope envelope) {
        return DefaultEnvelopeCodec.INSTANCE.encode(envelope);
    }

    private static Envelope deserialize(byte[] bytes) {
        return DefaultEnvelopeCodec.INSTANCE.decode(bytes);
    }

}