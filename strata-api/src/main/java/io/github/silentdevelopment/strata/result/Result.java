package io.github.silentdevelopment.strata.result;


import io.github.silentdevelopment.strata.layer.LayerReport;
import io.github.silentdevelopment.strata.layer.Role;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;

/**
 * Operation outcome returned by stack and layer methods.
 *
 * <p>Expected storage failures, conflicts, unsupported features, and not-found
 * outcomes are represented by this value rather than thrown exceptions. A
 * result may also carry per-layer reports for diagnostics.</p>
 *
 * @param <T> successful value type
 */
public final class Result<T> {

    private final Status status;
    private final T value;
    private final List<Failure> failures;
    private final List<LayerReport> layers;

    private Result(@NotNull Status status, @Nullable T value, @NotNull List<Failure> failures, @NotNull List<LayerReport> layers) {
        this.status = Objects.requireNonNull(status, "status");
        this.value = value;
        this.failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        this.layers = List.copyOf(Objects.requireNonNull(layers, "layers"));
    }

    /**
     * Creates a successful result without layer reports.
     *
     * @param value successful value, or {@code null} for void-like operations
     * @param <T> value type
     * @return success result
     */
    public static <T> Result<T> success(@Nullable T value) {
        return new Result<>(Status.SUCCESS, value, List.of(), List.of());
    }

    public static <T> Result<T> success(@Nullable T value, @NotNull List<LayerReport> layers) {
        return new Result<>(Status.SUCCESS, value, List.of(), layers);
    }

    public static <T> Result<T> successWithWarnings(@Nullable T value, @NotNull List<Failure> failures, @NotNull List<LayerReport> layers) {
        return new Result<>(Status.SUCCESS_WITH_WARNINGS, value, failures, layers);
    }

    public static <T> Result<T> notFound() {
        return new Result<>(Status.NOT_FOUND, null, List.of(), List.of());
    }

    public static <T> Result<T> notFound(@NotNull List<LayerReport> layers) {
        return new Result<>(Status.NOT_FOUND, null, List.of(), layers);
    }

    public static <T> Result<T> conflict(@NotNull Failure failure) {
        return new Result<>(Status.CONFLICT, null, List.of(failure), List.of());
    }

    public static <T> Result<T> conflict(@NotNull List<Failure> failures, @NotNull List<LayerReport> layers) {
        return new Result<>(Status.CONFLICT, null, failures, layers);
    }

    public static <T> Result<T> failure(@NotNull Failure failure) {
        return new Result<>(Status.FAILED, null, List.of(failure), List.of());
    }

    public static <T> Result<T> failure(@NotNull List<Failure> failures, @NotNull List<LayerReport> layers) {
        return new Result<>(Status.FAILED, null, failures, layers);
    }

    public static <T> Result<T> unsupported(@NotNull String message) {
        return new Result<>(Status.UNSUPPORTED, null, List.of(new Failure("stack", Role.DURABLE, message, null)), List.of());
    }

    /**
     * Returns the result status.
     *
     * @return result status
     */
    public Status status() {
        return status;
    }

    /**
     * Returns whether the operation completed successfully.
     *
     * @return {@code true} for success and success-with-warnings statuses
     */
    public boolean successful() {
        return status == Status.SUCCESS || status == Status.SUCCESS_WITH_WARNINGS;
    }

    /**
     * Returns whether warnings or failures are attached to the result.
     *
     * @return {@code true} when failure details are present or status indicates warnings
     */
    public boolean hasWarnings() {
        return status == Status.SUCCESS_WITH_WARNINGS || !failures.isEmpty();
    }

    /**
     * Returns the value as an optional.
     *
     * @return optional successful value
     */
    public Optional<T> optional() {
        return Optional.ofNullable(value);
    }

    /**
     * Returns the value or throws when no value is present.
     *
     * @return successful value
     * @throws NoSuchElementException when the result does not contain a value
     */
    public T valueOrThrow() {
        if (value == null) {
            throw new NoSuchElementException("Result has no value. Status: " + status);
        }
        return value;
    }

    /**
     * Returns failure and warning details.
     *
     * @return immutable failure list
     */
    public List<Failure> failures() {
        return failures;
    }

    /**
     * Returns per-layer operation reports.
     *
     * @return immutable layer report list
     */
    public List<LayerReport> layers() {
        return layers;
    }

    public Result<T> withLayers(@NotNull List<LayerReport> newLayers) {
        return new Result<>(status, value, failures, newLayers);
    }

    public Result<T> withWarnings(@NotNull List<Failure> warnings, @NotNull List<LayerReport> newLayers) {
        if (warnings.isEmpty()) {
            return new Result<>(status, value, failures, newLayers);
        }
        List<Failure> merged = new ArrayList<>(failures);
        merged.addAll(warnings);
        Status next = successful() ? Status.SUCCESS_WITH_WARNINGS : status;
        return new Result<>(next, value, merged, newLayers);
    }

}
