package io.github.silentdevelopment.strata.result;


/**
 * Result status for stack and layer operations.
 */
public enum Status {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    NOT_FOUND,
    CONFLICT,
    FAILED,
    PARTIAL,
    UNSUPPORTED
}
