package io.github.silentdevelopment.strata.durable.jdbc.mapped;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.math.BigDecimal;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;

public final class MappedJdbcRow {

    private final Map<String, Object> values;

    MappedJdbcRow(@NotNull Map<String, Object> values) {
        this.values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public @Nullable Object object(@NotNull String field) {
        return values.get(field);
    }

    public @NotNull Object required(@NotNull String field) {
        Object value = values.get(field);

        if (value == null) {
            throw new NoSuchElementException("No mapped JDBC value for field: " + field);
        }

        return value;
    }

    public @NotNull String string(@NotNull String field) {
        return String.valueOf(required(field));
    }

    public int integer(@NotNull String field) {
        Object value = required(field);

        if (value instanceof Number number) {
            return number.intValue();
        }

        return Integer.parseInt(String.valueOf(value));
    }

    public long longValue(@NotNull String field) {
        Object value = required(field);

        if (value instanceof Number number) {
            return number.longValue();
        }

        return Long.parseLong(String.valueOf(value));
    }

    public double doubleValue(@NotNull String field) {
        Object value = required(field);

        if (value instanceof Number number) {
            return number.doubleValue();
        }

        return Double.parseDouble(String.valueOf(value));
    }

    public boolean bool(@NotNull String field) {
        Object value = required(field);

        if (value instanceof Boolean bool) {
            return bool;
        }

        return Boolean.parseBoolean(String.valueOf(value));
    }

    public @NotNull BigDecimal decimal(@NotNull String field) {
        Object value = required(field);

        if (value instanceof BigDecimal decimal) {
            return decimal;
        }

        return new BigDecimal(String.valueOf(value));
    }

    public @NotNull Map<String, Object> values() {
        return values;
    }

}