/**
 * MongoDB durable layer implementation.
 *
 * <p>The layer stores metadata and indexes in MongoDB documents. TTL validity is
 * checked through Strata metadata and cleanup is supported by MongoDB indexes.</p>
 */
package io.github.silentdevelopment.strata.durable.mongodb;
