package io.github.silentdevelopment.strata.durable.jdbc.mapped;

import io.github.silentdevelopment.strata.Type;
import io.github.silentdevelopment.strata.codec.Codec;
import org.jetbrains.annotations.NotNull;
import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public final class MappedJdbcLayerBuilder<T> {

    private final Type<T> type;
    private final List<MappedJdbcColumn<T>> columns = new ArrayList<>();
    private String name = "mapped-jdbc";
    private String table;
    private String namespaceColumn = "namespace";
    private String pathColumn = "path";
    private String stampColumn = "stamp";
    private String createdAtColumn = "created_at";
    private String updatedAtColumn = "updated_at";
    private String expiresAtColumn = "expires_at";
    private Codec<T> codec;
    private Function<MappedJdbcRow, T> mapper;

    MappedJdbcLayerBuilder(@NotNull Type<T> type) {
        this.type = Objects.requireNonNull(type, "type");
        this.table = type.id();
    }

    public @NotNull MappedJdbcLayerBuilder<T> named(@NotNull String name) {
        this.name = Objects.requireNonNull(name, "name");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> table(@NotNull String table) {
        this.table = MappedJdbcColumn.validateIdentifier(table, "table");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> namespaceColumn(@NotNull String namespaceColumn) {
        this.namespaceColumn = MappedJdbcColumn.validateIdentifier(namespaceColumn, "namespaceColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> pathColumn(@NotNull String pathColumn) {
        this.pathColumn = MappedJdbcColumn.validateIdentifier(pathColumn, "pathColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> stampColumn(@NotNull String stampColumn) {
        this.stampColumn = MappedJdbcColumn.validateIdentifier(stampColumn, "stampColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> createdAtColumn(@NotNull String createdAtColumn) {
        this.createdAtColumn = MappedJdbcColumn.validateIdentifier(createdAtColumn, "createdAtColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> updatedAtColumn(@NotNull String updatedAtColumn) {
        this.updatedAtColumn = MappedJdbcColumn.validateIdentifier(updatedAtColumn, "updatedAtColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> expiresAtColumn(@NotNull String expiresAtColumn) {
        this.expiresAtColumn = MappedJdbcColumn.validateIdentifier(expiresAtColumn, "expiresAtColumn");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> codec(@NotNull Codec<T> codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> mapper(@NotNull Function<MappedJdbcRow, T> mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        return this;
    }

    public <V> @NotNull MappedJdbcLayerBuilder<T> column(@NotNull String field, @NotNull String column, @NotNull String definition, @NotNull Class<V> javaType, @NotNull Function<T, V> extractor) {
        columns.add(new MappedJdbcColumn<>(field, column, definition, javaType, extractor, false));
        return this;
    }

    public <V> @NotNull MappedJdbcLayerBuilder<T> indexedColumn(@NotNull String field, @NotNull String column, @NotNull String definition, @NotNull Class<V> javaType, @NotNull Function<T, V> extractor) {
        columns.add(new MappedJdbcColumn<>(field, column, definition, javaType, extractor, true));
        return this;
    }

    public @NotNull MappedJdbcLayerBuilder<T> stringColumn(@NotNull String field, @NotNull Function<T, String> extractor, int length) {
        return column(field, field, "VARCHAR(" + length + ") NOT NULL", String.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> nullableStringColumn(@NotNull String field, @NotNull Function<T, String> extractor, int length) {
        return column(field, field, "VARCHAR(" + length + ") NULL", String.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> indexedStringColumn(@NotNull String field, @NotNull Function<T, String> extractor, int length) {
        return indexedColumn(field, field, "VARCHAR(" + length + ") NOT NULL", String.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> intColumn(@NotNull String field, @NotNull Function<T, Integer> extractor) {
        return column(field, field, "INT NOT NULL", Integer.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> indexedIntColumn(@NotNull String field, @NotNull Function<T, Integer> extractor) {
        return indexedColumn(field, field, "INT NOT NULL", Integer.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> longColumn(@NotNull String field, @NotNull Function<T, Long> extractor) {
        return column(field, field, "BIGINT NOT NULL", Long.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> indexedLongColumn(@NotNull String field, @NotNull Function<T, Long> extractor) {
        return indexedColumn(field, field, "BIGINT NOT NULL", Long.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> doubleColumn(@NotNull String field, @NotNull Function<T, Double> extractor) {
        return column(field, field, "DOUBLE NOT NULL", Double.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> indexedDoubleColumn(@NotNull String field, @NotNull Function<T, Double> extractor) {
        return indexedColumn(field, field, "DOUBLE NOT NULL", Double.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> booleanColumn(@NotNull String field, @NotNull Function<T, Boolean> extractor) {
        return column(field, field, "BOOLEAN NOT NULL", Boolean.class, extractor);
    }

    public @NotNull MappedJdbcLayerBuilder<T> indexedBooleanColumn(@NotNull String field, @NotNull Function<T, Boolean> extractor) {
        return indexedColumn(field, field, "BOOLEAN NOT NULL", Boolean.class, extractor);
    }

    public @NotNull MappedJdbcLayer<T> build(@NotNull DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");

        if (columns.isEmpty()) {
            throw new IllegalStateException("At least one mapped JDBC column must be registered.");
        }

        if (codec == null) {
            throw new IllegalStateException("A codec must be configured.");
        }

        if (mapper == null) {
            throw new IllegalStateException("A row mapper must be configured.");
        }

        return new MappedJdbcLayer<>(name, type, dataSource, table, namespaceColumn, pathColumn, stampColumn, createdAtColumn, updatedAtColumn, expiresAtColumn, List.copyOf(columns), codec, mapper);
    }

}