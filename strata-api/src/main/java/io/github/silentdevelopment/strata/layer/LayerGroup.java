package io.github.silentdevelopment.strata.layer;


import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;

/**
 * Registration group for layers that share a role and execution mode.
 */
public record LayerGroup(@NotNull Role role, @NotNull GroupMode mode, @NotNull List<LayerRegistration> layers) {

    public LayerGroup {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(mode, "mode");
        layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
        for (LayerRegistration registration : layers) {
            if (registration.layer().role() != role) {
                throw new IllegalArgumentException("Layer role mismatch. Expected " + role + " but got " + registration.layer().role());
            }
        }
    }

    public static LayerGroup of(@NotNull Role role, @NotNull Layer layer) {
        return new LayerGroup(role, GroupMode.SINGLE, List.of(new LayerRegistration(layer, LayerOptions.defaults())));
    }

    public boolean empty() {
        return layers.isEmpty();
    }

}
