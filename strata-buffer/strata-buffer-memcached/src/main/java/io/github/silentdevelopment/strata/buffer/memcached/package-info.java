/**
 * Memcached buffer layer implementation.
 *
 * <p>The layer provides key-value, TTL, and CAS conflict support. Query and index
 * operations are unsupported because Memcached has no safe enumeration model.</p>
 */
package io.github.silentdevelopment.strata.buffer.memcached;
