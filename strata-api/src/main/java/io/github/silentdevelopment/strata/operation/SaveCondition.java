package io.github.silentdevelopment.strata.operation;


import io.github.silentdevelopment.strata.entry.Stamp;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;

/**
 * Conditional save requirement for optimistic concurrency.
 *
 * <p>A matching stamp condition protects against stale writes by requiring the
 * currently stored record to carry the expected stamp before the save succeeds.</p>
 *
 * @param expectedStamp required stamp, or {@code null} when any existing stamp is accepted
 */
public record SaveCondition(@Nullable Stamp expectedStamp) {

    /**
     * Creates an unconditional save condition.
     *
     * @return unconditional condition
     */
    public static SaveCondition any() {
        return new SaveCondition(null);
    }

    /**
     * Creates a condition that requires the stored stamp to match.
     *
     * @param stamp expected stamp
     * @return matching-stamp condition
     */
    public static SaveCondition matching(@NotNull Stamp stamp) {
        return new SaveCondition(Objects.requireNonNull(stamp, "stamp"));
    }

    /**
     * Returns the expected stamp when this condition is conditional.
     *
     * @return optional expected stamp
     */
    public Optional<Stamp> expectedStampOptional() {
        return Optional.ofNullable(expectedStamp);
    }

}
