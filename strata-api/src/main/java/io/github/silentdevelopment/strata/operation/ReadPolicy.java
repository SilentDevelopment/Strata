package io.github.silentdevelopment.strata.operation;


/**
 * Read ordering policy for stack load operations.
 */
public enum ReadPolicy {
    /** Reads only from durable storage. */
    DURABLE,
    /** Reads durable storage and repairs non-authoritative layers after a hit. */
    DURABLE_WITH_REPAIR,
    /** Reads soft, buffer, then durable layers according to stack order. */
    SOFT_BUFFER_DURABLE,
    /** Returns the first available value and may return stale non-authoritative data. */
    FIRST_AVAILABLE,
    /** Accepts non-expired cached values before consulting durable storage. */
    FRESH_ENOUGH
}
