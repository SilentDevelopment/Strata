package io.github.silentdevelopment.strata.operation;


/**
 * Delete propagation policy for stack delete operations.
 */
public enum DeletePolicy {
    /** Deletes or invalidates durable storage and refreshes non-authoritative state according to stack behavior. */
    DURABLE_INVALIDATE,
    /** Deletes from all participating layers. */
    ALL,
    /** Writes a tombstone marker where supported. */
    TOMBSTONE,
    /** Deletes only from durable storage; non-authoritative copies may remain until invalidated elsewhere. */
    DURABLE_ONLY
}
