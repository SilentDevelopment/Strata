package io.github.silentdevelopment.strata.layer;


/**
 * Execution mode for a group of layers with the same role.
 */
public enum GroupMode {
    SINGLE,
    PRIMARY_FALLBACK,
    MIRROR_ALL,
    FIRST_SUCCESS,
    QUORUM
}
