package io.github.silentdevelopment.strata.operation;


/**
 * Write ordering and durability policy for save operations.
 */
public enum WritePolicy {
    /** Writes durable storage first and then updates acceleration layers. */
    DURABLE_FIRST,
    /** Writes durable storage and invalidates non-authoritative copies. */
    DURABLE_INVALIDATE,
    /** Writes through each configured layer. */
    WRITE_THROUGH,
    /** Requires every participating layer write to succeed. */
    ALL_REQUIRED
}
