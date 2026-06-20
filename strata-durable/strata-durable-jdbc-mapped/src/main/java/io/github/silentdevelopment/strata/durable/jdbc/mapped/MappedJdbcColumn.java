package io.github.silentdevelopment.strata.durable.jdbc.mapped;

import org.jetbrains.annotations.NotNull;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.function.Function;

public final class MappedJdbcColumn<T> {

    private final String field;
    private final String column;
    private final String definition;
    private final Class<?> javaType;
    private final Function<T, ?> extractor;
    private final boolean indexed;

    MappedJdbcColumn(@NotNull String field, @NotNull String column, @NotNull String definition, @NotNull Class<?> javaType, @NotNull Function<T, ?> extractor, boolean indexed) {
        this.field = validateIdentifier(field, "field");
        this.column = validateIdentifier(column, "column");
        this.definition = Objects.requireNonNull(definition, "definition");
        this.javaType = Objects.requireNonNull(javaType, "javaType");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.indexed = indexed;

        if (definition.isBlank()) {
            throw new IllegalArgumentException("Column definition cannot be blank.");
        }
    }

    public @NotNull String field() {
        return field;
    }

    public @NotNull String column() {
        return column;
    }

    public @NotNull String definition() {
        return definition;
    }

    public @NotNull Class<?> javaType() {
        return javaType;
    }

    public boolean indexed() {
        return indexed;
    }

    Object value(T value) {
        return extractor.apply(value);
    }

    Object parse(String value) {
        if (javaType == String.class) {
            return value;
        }

        if (javaType == Integer.class || javaType == int.class) {
            return Integer.parseInt(value);
        }

        if (javaType == Long.class || javaType == long.class) {
            return Long.parseLong(value);
        }

        if (javaType == Double.class || javaType == double.class) {
            return Double.parseDouble(value);
        }

        if (javaType == Float.class || javaType == float.class) {
            return Float.parseFloat(value);
        }

        if (javaType == Boolean.class || javaType == boolean.class) {
            return Boolean.parseBoolean(value);
        }

        if (javaType == BigDecimal.class) {
            return new BigDecimal(value);
        }

        if (Enum.class.isAssignableFrom(javaType)) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object enumValue = Enum.valueOf((Class<? extends Enum>) javaType.asSubclass(Enum.class), value);
            return enumValue;
        }

        return value;
    }

    String stringValue(Object value) {
        if (value == null) {
            return "";
        }

        return String.valueOf(value);
    }

    static String validateIdentifier(String value, String name) {
        Objects.requireNonNull(value, name);

        if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(name + " must be a simple SQL identifier: " + value);
        }

        return value;
    }

}