package io.github.silentdevelopment.strata.journal.file;


import io.github.silentdevelopment.strata.journal.Journal;
import io.github.silentdevelopment.strata.journal.JournalEntry;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * File-backed journal implementation.
 */
public final class FileJournal implements Journal {

    private final Path file;

    private FileJournal(@NotNull Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    public static FileJournal create(@NotNull Path file) {
        return new FileJournal(file);
    }

    @Override
    public synchronized void append(@NotNull JournalEntry entry) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.createdAt() + "\t" + entry.operation() + "\t" + entry.key() + "\t" + entry.message().replace('\t', ' ') + System.lineSeparator(), StandardCharsets.UTF_8, Files.exists(file) ? java.nio.file.StandardOpenOption.APPEND : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to append journal entry.", exception);
        }
    }

    @Override
    public synchronized @NotNull List<JournalEntry> entries() {
        if (!Files.exists(file)) {
            return List.of();
        }
        try {
            List<JournalEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) {
                    continue;
                }
                entries.add(new JournalEntry(Instant.parse(parts[0]), parts[1], parts[2], parts[3]));
            }
            return entries;
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read journal entries.", exception);
        }
    }

    @Override
    public synchronized void clear() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to clear journal.", exception);
        }
    }

}
