package io.github.silentdevelopment.strata.layer;


import io.github.silentdevelopment.strata.result.Failure;
import io.github.silentdevelopment.strata.result.Status;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Per-layer diagnostic report attached to an operation result.
 */
public record LayerReport(@NotNull String layer, @NotNull Role role, @NotNull Status status, @NotNull Duration duration, @NotNull List<Failure> failures) {

    public LayerReport {
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(duration, "duration");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
    }

    public static LayerReport success(@NotNull Layer layer, @NotNull Duration duration) {
        return new LayerReport(layer.name(), layer.role(), Status.SUCCESS, duration, List.of());
    }

    public static LayerReport failure(@NotNull Layer layer, @NotNull Duration duration, @NotNull Failure failure) {
        return new LayerReport(layer.name(), layer.role(), Status.FAILED, duration, List.of(failure));
    }

}
