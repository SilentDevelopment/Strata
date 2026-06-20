package io.github.silentdevelopment.strata.operation;


/**
 * Repair behavior applied after durable reads discover stale or missing
 * non-authoritative copies.
 */
public enum ReadRepair {
    /** Disables read repair. */
    DISABLED,
    /** Attempts repair without failing the read when repair fails. */
    BEST_EFFORT,
    /** Requires repair to succeed for the read to be reported as successful. */
    REQUIRED
}
