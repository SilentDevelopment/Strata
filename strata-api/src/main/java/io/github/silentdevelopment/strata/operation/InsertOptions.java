package io.github.silentdevelopment.strata.operation;


import io.github.silentdevelopment.strata.Namespace;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Insert operation options containing namespace, collection, and save behavior.
 */
public record InsertOptions(@NotNull Namespace namespace, @NotNull String collection, @NotNull SaveOptions saveOptions) {

    public InsertOptions {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(collection, "collection");
        Objects.requireNonNull(saveOptions, "saveOptions");
    }

    public static InsertOptions of(@NotNull Namespace namespace, @NotNull String collection) {
        return new InsertOptions(namespace, collection, SaveOptions.defaults());
    }

}
