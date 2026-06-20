package io.github.silentdevelopment.strata.journal;


import org.jetbrains.annotations.NotNull;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable journal record containing key, action, timestamp, and metadata.
 */
public record JournalEntry(@NotNull Instant createdAt, @NotNull String operation, @NotNull String key, @NotNull String message) {

    public JournalEntry {
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(message, "message");
    }

    public static JournalEntry of(@NotNull String operation, @NotNull String key, @NotNull String message) {
        return new JournalEntry(Instant.now(), operation, key, message);
    }

}
