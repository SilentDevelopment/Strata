package io.github.silentdevelopment.strata.lock;


/**
 * Acquired lock handle that releases ownership when closed.
 */
public interface LockHandle extends AutoCloseable {

    boolean acquired();

    @Override
    void close();

}
