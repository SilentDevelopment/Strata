/**
 * Lettuce-backed Redis buffer layer.
 *
 * <p>The layer stores encoded envelopes in Redis and maintains Strata-managed
 * index sets for query support without using Redis KEYS.</p>
 */
package io.github.silentdevelopment.strata.buffer.redis.lettuce;
