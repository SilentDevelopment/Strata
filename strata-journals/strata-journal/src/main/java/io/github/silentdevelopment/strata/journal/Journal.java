package io.github.silentdevelopment.strata.journal;


import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * Journal contract for appending storage-related entries.
 */
public interface Journal {

    void append(@NotNull JournalEntry entry);

    @NotNull List<JournalEntry> entries();

    void clear();

}
