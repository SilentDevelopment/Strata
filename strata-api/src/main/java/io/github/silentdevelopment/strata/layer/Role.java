package io.github.silentdevelopment.strata.layer;


/**
 * Role of a layer within a Strata storage stack.
 */
public enum Role {
    /** Local, volatile, non-authoritative storage, usually memory. */
    SOFT,
    /** Intermediate, non-authoritative acceleration storage, usually Redis or Memcached. */
    BUFFER,
    /** Persistent, authoritative storage, usually JDBC, MongoDB, or file storage. */
    DURABLE
}
