package io.github.silentdevelopment.strata.query;

import org.jetbrains.annotations.NotNull;
import java.util.Objects;

public record Sort(@NotNull String field, @NotNull SortDirection direction) {

    public Sort {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(direction, "direction");

        if (field.isBlank()) {
            throw new IllegalArgumentException("Sort field cannot be blank.");
        }
    }

    public static @NotNull Sort ascending(@NotNull String field) {
        return new Sort(field, SortDirection.ASC);
    }

    public static @NotNull Sort descending(@NotNull String field) {
        return new Sort(field, SortDirection.DESC);
    }

}