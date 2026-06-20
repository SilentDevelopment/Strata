package io.github.silentdevelopment.strata.layer;


/**
 * Capability report for a stack or individual layer.
 */
public record Capabilities(boolean keyValue, boolean query, boolean index, boolean ttl, boolean lock, boolean durable, boolean transactional, boolean conditionalWrite, boolean batch) {

    public static Capabilities keyValue(Role role) {
        return new Capabilities(true, false, false, false, false, role == Role.DURABLE, false, true, false);
    }

    public Capabilities withQuery(boolean value) {
        return new Capabilities(keyValue, value, index, ttl, lock, durable, transactional, conditionalWrite, batch);
    }

    public Capabilities withIndex(boolean value) {
        return new Capabilities(keyValue, query, value, ttl, lock, durable, transactional, conditionalWrite, batch);
    }

    public Capabilities withTtl(boolean value) {
        return new Capabilities(keyValue, query, index, value, lock, durable, transactional, conditionalWrite, batch);
    }

    public Capabilities merge(Capabilities other) {
        return new Capabilities(keyValue || other.keyValue, query || other.query, index || other.index, ttl || other.ttl, lock || other.lock, durable || other.durable, transactional || other.transactional, conditionalWrite || other.conditionalWrite, batch || other.batch);
    }

}
