package io.github.silentdevelopment.strata.operation;


import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Load operation options controlling read policy and read repair.
 */
public record LoadOptions(@NotNull ReadPolicy readPolicy, @NotNull ReadRepair readRepair) {

    public LoadOptions {
        Objects.requireNonNull(readPolicy, "readPolicy");
        Objects.requireNonNull(readRepair, "readRepair");
    }

    public static LoadOptions defaults() {
        return new LoadOptions(ReadPolicy.DURABLE_WITH_REPAIR, ReadRepair.BEST_EFFORT);
    }

    public LoadOptions withReadPolicy(@NotNull ReadPolicy policy) {
        return new LoadOptions(policy, readRepair);
    }

}
