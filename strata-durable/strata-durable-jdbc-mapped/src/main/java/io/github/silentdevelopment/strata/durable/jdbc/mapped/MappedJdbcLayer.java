package io.github.silentdevelopment.strata.durable.jdbc.mapped;

import io.github.silentdevelopment.strata.Key;
import io.github.silentdevelopment.strata.Namespace;
import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import io.github.silentdevelopment.strata.codec.Encoded;
import io.github.silentdevelopment.strata.entry.Envelope;
import io.github.silentdevelopment.strata.entry.Metadata;
import io.github.silentdevelopment.strata.entry.Stamp;
import io.github.silentdevelopment.strata.layer.Capabilities;
import io.github.silentdevelopment.strata.layer.Layer;
import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import io.github.silentdevelopment.strata.operation.OperationContext;
import io.github.silentdevelopment.strata.operation.SaveCondition;
import io.github.silentdevelopment.strata.query.Query;
import io.github.silentdevelopment.strata.query.Sort;
import io.github.silentdevelopment.strata.query.SortDirection;
import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Result;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class MappedJdbcLayer<T> implements Layer {

    private final String name;
    private final Type<T> type;
    private final DataSource dataSource;
    private final String table;
    private final String namespaceColumn;
    private final String pathColumn;
    private final String stampColumn;
    private final String createdAtColumn;
    private final String updatedAtColumn;
    private final String expiresAtColumn;
    private final List<MappedJdbcColumn<T>> columns;
    private final Codec<T> codec;
    private final Function<MappedJdbcRow, T> mapper;

    MappedJdbcLayer(@NotNull String name, @NotNull Type<T> type, @NotNull DataSource dataSource, @NotNull String table, @NotNull String namespaceColumn, @NotNull String pathColumn, @NotNull String stampColumn, @NotNull String createdAtColumn, @NotNull String updatedAtColumn, @NotNull String expiresAtColumn, @NotNull List<MappedJdbcColumn<T>> columns, @NotNull Codec<T> codec, @NotNull Function<MappedJdbcRow, T> mapper) {
        this.name = Objects.requireNonNull(name, "name");
        this.type = Objects.requireNonNull(type, "type");
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.table = MappedJdbcColumn.validateIdentifier(table, "table");
        this.namespaceColumn = MappedJdbcColumn.validateIdentifier(namespaceColumn, "namespaceColumn");
        this.pathColumn = MappedJdbcColumn.validateIdentifier(pathColumn, "pathColumn");
        this.stampColumn = MappedJdbcColumn.validateIdentifier(stampColumn, "stampColumn");
        this.createdAtColumn = MappedJdbcColumn.validateIdentifier(createdAtColumn, "createdAtColumn");
        this.updatedAtColumn = MappedJdbcColumn.validateIdentifier(updatedAtColumn, "updatedAtColumn");
        this.expiresAtColumn = MappedJdbcColumn.validateIdentifier(expiresAtColumn, "expiresAtColumn");
        this.columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        this.codec = Objects.requireNonNull(codec, "codec");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    public static <T> @NotNull MappedJdbcLayerBuilder<T> forType(@NotNull Type<T> type) {
        return new MappedJdbcLayerBuilder<>(type);
    }

    public void createSchema() throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(createTableSql());
            createConfiguredIndexes(statement);
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
    public @NotNull CompletableFuture<Result<List<Envelope>>> query(@NotNull Type<?> queryType, @NotNull Query query, @NotNull OperationContext context) {
        return CompletableFuture.supplyAsync(() -> queryNow(queryType, query), context.executor());
    }

    private Result<Envelope> loadNow(Key<?> key) {
        Instant start = Instant.now();

        if (!key.type().id().equals(type.id())) {
            return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
        }

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(selectByKeySql())) {
            statement.setString(1, key.namespace().value());
            statement.setString(2, key.path());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
                }

                Envelope envelope = envelope(resultSet);

                if (envelope.metadata().expired()) {
                    deleteNow(key);
                    return Result.notFound(List.of(new LayerReport(name, role(), Status.NOT_FOUND, Duration.between(start, Instant.now()), List.of())));
                }

                return Result.success(envelope, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
            }
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> saveNow(Envelope envelope, OperationContext context) {
        Instant start = Instant.now();

        if (!envelope.key().type().id().equals(type.id())) {
            Failure failure = new Failure(name, role(), "Unsupported type for mapped JDBC layer: " + envelope.key().type().id(), null);
            return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
        }

        try (Connection connection = dataSource.getConnection()) {
            T value = codec.decode(envelope.encoded(), type);
            SaveCondition condition = context.saveOptions().condition();
            Instant expiresAt = envelope.metadata().expiresAt();

            if (context.saveOptions().ttl() != null) {
                expiresAt = Instant.now().plus(context.saveOptions().ttl());
            }

            if (condition.expectedStamp() != null) {
                return conditionalUpdate(connection, envelope, value, expiresAt, condition, start);
            }

            int updated = update(connection, envelope, value, expiresAt);

            if (updated == 0) {
                insert(connection, envelope, value, expiresAt);
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> deleteNow(Key<?> key) {
        Instant start = Instant.now();

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(deleteSql())) {
            statement.setString(1, key.namespace().value());
            statement.setString(2, key.path());
            statement.executeUpdate();

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<List<Envelope>> queryNow(Type<?> queryType, Query query) {
        Instant start = Instant.now();

        if (!queryType.id().equals(type.id())) {
            return Result.success(List.of(), List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        }

        MappedJdbcColumn<T> queryColumn = null;

        if (query.operator() != Query.Operator.ALL) {
            queryColumn = columnByField(query.field());

            if (queryColumn == null) {
                return unsupported(start, "Mapped JDBC layer does not support query field: " + query.field());
            }
        }

        for (Sort sort : query.sorts()) {
            if (columnByField(sort.field()) == null) {
                return unsupported(start, "Mapped JDBC layer does not support sort field: " + sort.field());
            }
        }

        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(querySql(query, queryColumn))) {
            if (queryColumn != null) {
                statement.setObject(1, queryColumn.parse(query.value()));
            }

            List<Envelope> result = new ArrayList<>();

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    Envelope envelope = envelope(resultSet);

                    if (envelope.metadata().expired()) {
                        deleteNow(envelope.key());
                        continue;
                    }

                    result.add(envelope);
                }
            }

            return Result.success(result, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        } catch (Exception exception) {
            return failure(start, exception);
        }
    }

    private Result<Void> conditionalUpdate(Connection connection, Envelope envelope, T value, Instant expiresAt, SaveCondition condition, Instant start) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(updateSql(true))) {
            int index = bindUpdateValues(statement, envelope, value, expiresAt);
            statement.setString(index++, envelope.key().namespace().value());
            statement.setString(index++, envelope.key().path());
            statement.setString(index, condition.expectedStamp().value());

            int updated = statement.executeUpdate();

            if (updated == 0) {
                Failure failure = new Failure(name, role(), "Stamp conflict.", null);
                return Result.conflict(List.of(failure), List.of(new LayerReport(name, role(), Status.CONFLICT, Duration.between(start, Instant.now()), List.of(failure))));
            }

            return Result.success(null, List.of(LayerReport.success(this, Duration.between(start, Instant.now()))));
        }
    }

    private int update(Connection connection, Envelope envelope, T value, Instant expiresAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(updateSql(false))) {
            int index = bindUpdateValues(statement, envelope, value, expiresAt);
            statement.setString(index++, envelope.key().namespace().value());
            statement.setString(index, envelope.key().path());
            return statement.executeUpdate();
        }
    }

    private void insert(Connection connection, Envelope envelope, T value, Instant expiresAt) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(insertSql())) {
            int index = 1;
            statement.setString(index++, envelope.key().namespace().value());
            statement.setString(index++, envelope.key().path());
            statement.setString(index++, envelope.stamp().value());
            statement.setLong(index++, envelope.metadata().createdAt().toEpochMilli());
            statement.setLong(index++, Instant.now().toEpochMilli());
            setNullableInstant(statement, index++, expiresAt);

            for (MappedJdbcColumn<T> column : columns) {
                statement.setObject(index++, column.value(value));
            }

            statement.executeUpdate();
        }
    }

    private int bindUpdateValues(PreparedStatement statement, Envelope envelope, T value, Instant expiresAt) throws Exception {
        int index = 1;
        statement.setString(index++, envelope.stamp().value());
        statement.setLong(index++, Instant.now().toEpochMilli());
        setNullableInstant(statement, index++, expiresAt);

        for (MappedJdbcColumn<T> column : columns) {
            statement.setObject(index++, column.value(value));
        }

        return index;
    }

    private Envelope envelope(ResultSet resultSet) throws Exception {
        String namespace = resultSet.getString(namespaceColumn);
        String path = resultSet.getString(pathColumn);
        String stamp = resultSet.getString(stampColumn);
        Instant createdAt = Instant.ofEpochMilli(resultSet.getLong(createdAtColumn));
        Instant updatedAt = Instant.ofEpochMilli(resultSet.getLong(updatedAtColumn));
        Long expiresAtMillis = nullableLong(resultSet, expiresAtColumn);
        Instant expiresAt = expiresAtMillis == null ? null : Instant.ofEpochMilli(expiresAtMillis);

        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, String> indexes = new LinkedHashMap<>();

        for (MappedJdbcColumn<T> column : columns) {
            Object value = resultSet.getObject(column.column());
            values.put(column.field(), value);
            indexes.put(column.field(), column.stringValue(value));
        }

        T value = mapper.apply(new MappedJdbcRow(values));
        Encoded encoded = codec.encode(value);
        Metadata metadata = new Metadata(createdAt, updatedAt, expiresAt, Map.of(), indexes);
        Key<T> key = Key.of(Namespace.of(namespace), type, path);

        return new Envelope(key, encoded, Stamp.of(stamp), metadata, false);
    }

    private String createTableSql() {
        StringBuilder builder = new StringBuilder();
        builder.append("CREATE TABLE IF NOT EXISTS ").append(table).append(" (");
        builder.append(namespaceColumn).append(" VARCHAR(255) NOT NULL, ");
        builder.append(pathColumn).append(" VARCHAR(1024) NOT NULL, ");
        builder.append(stampColumn).append(" VARCHAR(255) NOT NULL, ");
        builder.append(createdAtColumn).append(" BIGINT NOT NULL, ");
        builder.append(updatedAtColumn).append(" BIGINT NOT NULL, ");
        builder.append(expiresAtColumn).append(" BIGINT NULL");

        for (MappedJdbcColumn<T> column : columns) {
            builder.append(", ").append(column.column()).append(" ").append(column.definition());
        }

        builder.append(", PRIMARY KEY(").append(namespaceColumn).append(", ").append(pathColumn).append("))");
        return builder.toString();
    }

    private void createConfiguredIndexes(Statement statement) throws Exception {
        for (MappedJdbcColumn<T> column : columns) {
            if (!column.indexed()) {
                continue;
            }

            statement.executeUpdate("CREATE INDEX " + indexName(column) + " ON " + table + " (" + column.column() + ")");
        }
    }

    private String selectColumnsSql() {
        StringBuilder builder = new StringBuilder();
        builder.append(namespaceColumn).append(", ");
        builder.append(pathColumn).append(", ");
        builder.append(stampColumn).append(", ");
        builder.append(createdAtColumn).append(", ");
        builder.append(updatedAtColumn).append(", ");
        builder.append(expiresAtColumn);

        for (MappedJdbcColumn<T> column : columns) {
            builder.append(", ").append(column.column());
        }

        return builder.toString();
    }

    private String selectByKeySql() {
        return "SELECT " + selectColumnsSql() + " FROM " + table + " WHERE " + namespaceColumn + " = ? AND " + pathColumn + " = ?";
    }

    private String deleteSql() {
        return "DELETE FROM " + table + " WHERE " + namespaceColumn + " = ? AND " + pathColumn + " = ?";
    }

    private String updateSql(boolean conditional) {
        StringBuilder builder = new StringBuilder();
        builder.append("UPDATE ").append(table).append(" SET ");
        builder.append(stampColumn).append(" = ?, ");
        builder.append(updatedAtColumn).append(" = ?, ");
        builder.append(expiresAtColumn).append(" = ?");

        for (MappedJdbcColumn<T> column : columns) {
            builder.append(", ").append(column.column()).append(" = ?");
        }

        builder.append(" WHERE ").append(namespaceColumn).append(" = ? AND ").append(pathColumn).append(" = ?");

        if (conditional) {
            builder.append(" AND ").append(stampColumn).append(" = ?");
        }

        return builder.toString();
    }

    private String insertSql() {
        StringBuilder names = new StringBuilder();
        StringBuilder values = new StringBuilder();

        appendInsertColumn(names, values, namespaceColumn);
        appendInsertColumn(names, values, pathColumn);
        appendInsertColumn(names, values, stampColumn);
        appendInsertColumn(names, values, createdAtColumn);
        appendInsertColumn(names, values, updatedAtColumn);
        appendInsertColumn(names, values, expiresAtColumn);

        for (MappedJdbcColumn<T> column : columns) {
            appendInsertColumn(names, values, column.column());
        }

        return "INSERT INTO " + table + " (" + names + ") VALUES (" + values + ")";
    }

    private String querySql(Query query, MappedJdbcColumn<T> queryColumn) {
        StringBuilder builder = new StringBuilder();
        builder.append("SELECT ").append(selectColumnsSql()).append(" FROM ").append(table);

        if (query.operator() != Query.Operator.ALL) {
            builder.append(" WHERE ").append(queryColumn.column()).append(" ").append(sqlOperator(query.operator())).append(" ?");
        }

        appendSorts(builder, query);

        if (query.limit() > 0) {
            builder.append(" LIMIT ").append(query.limit());
        }

        if (query.offset() > 0) {
            builder.append(" OFFSET ").append(query.offset());
        }

        return builder.toString();
    }

    private String sqlOperator(Query.Operator operator) {
        return switch (operator) {
            case EQ -> "=";
            case NE -> "<>";
            case GT -> ">";
            case GTE -> ">=";
            case LT -> "<";
            case LTE -> "<=";
            case ALL -> throw new IllegalArgumentException("ALL has no SQL comparison operator.");
        };
    }

    private MappedJdbcColumn<T> columnByField(String field) {
        for (MappedJdbcColumn<T> column : columns) {
            if (column.field().equals(field)) {
                return column;
            }
        }

        return null;
    }

    private String indexName(MappedJdbcColumn<T> column) {
        String name = "idx_" + table + "_" + column.column();

        if (name.length() <= 60) {
            return name;
        }

        return name.substring(0, 60);
    }

    private static void appendInsertColumn(StringBuilder names, StringBuilder values, String column) {
        if (!names.isEmpty()) {
            names.append(", ");
            values.append(", ");
        }

        names.append(column);
        values.append("?");
    }

    private void appendSorts(StringBuilder builder, Query query) {
        if (query.sorts().isEmpty()) {
            return;
        }

        builder.append(" ORDER BY ");

        for (int index = 0; index < query.sorts().size(); index++) {
            Sort sort = query.sorts().get(index);
            MappedJdbcColumn<T> column = columnByField(sort.field());

            if (column == null) {
                throw new IllegalArgumentException("Unknown mapped JDBC sort field: " + sort.field());
            }

            if (index > 0) {
                builder.append(", ");
            }

            builder.append(column.column()).append(" ").append(sqlSortDirection(sort.direction()));
        }
    }

    private String sqlSortDirection(SortDirection direction) {
        return switch (direction) {
            case ASC -> "ASC";
            case DESC -> "DESC";
        };
    }

    private <V> Result<V> unsupported(Instant start, String message) {
        Failure failure = new Failure(name, role(), message, null);
        LayerReport report = new LayerReport(name, role(), Status.UNSUPPORTED, Duration.between(start, Instant.now()), List.of(failure));
        return Result.<V>unsupported(message).withWarnings(List.of(failure), List.of(report));
    }

    private static void setNullableInstant(PreparedStatement statement, int index, Instant instant) throws Exception {
        if (instant == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }

        statement.setLong(index, instant.toEpochMilli());
    }

    private static Long nullableLong(ResultSet resultSet, String column) throws Exception {
        long value = resultSet.getLong(column);

        if (resultSet.wasNull()) {
            return null;
        }

        return value;
    }

    private <V> Result<V> failure(Instant start, Exception exception) {
        Failure failure = Failure.of(name, role(), exception);
        return Result.failure(List.of(failure), List.of(LayerReport.failure(this, Duration.between(start, Instant.now()), failure)));
    }

}