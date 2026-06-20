package io.github.silentdevelopment.strata.layer;


import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Layer and options pair used by stack configuration.
 */
public record LayerRegistration(@NotNull Layer layer, @NotNull LayerOptions options) {

    public LayerRegistration {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(options, "options");
    }

}
