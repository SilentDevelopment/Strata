package io.github.silentdevelopment.strata.lock.redis.redisson;


import io.github.silentdevelopment.strata.lock.LockHandle;
import io.github.silentdevelopment.strata.lock.LockManager;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Redisson-backed lock manager.
 *
 * <p>Redisson locks are thread-bound and returned handles must be closed by the owner.</p>
 */
public final class RedissonLockManager implements LockManager {

    private final RedissonClient redisson;
    private final String prefix;

    private RedissonLockManager(@NotNull RedissonClient redisson, @NotNull String prefix) {
        this.redisson = Objects.requireNonNull(redisson, "redisson");
        this.prefix = normalizePrefix(prefix);
    }

    public static @NotNull RedissonLockManager create(@NotNull RedissonClient redisson) {
        return named(redisson, "strata:lock");
    }

    public static @NotNull RedissonLockManager named(@NotNull RedissonClient redisson, @NotNull String prefix) {
        return new RedissonLockManager(redisson, prefix);
    }

    @Override
    public @NotNull CompletableFuture<LockHandle> acquire(@NotNull String key, @NotNull Duration waitTime, @NotNull Duration leaseTime) {
        try {
            return CompletableFuture.completedFuture(acquireNow(key, waitTime, leaseTime));
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private LockHandle acquireNow(String key, Duration waitTime, Duration leaseTime) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(waitTime, "waitTime");
        Objects.requireNonNull(leaseTime, "leaseTime");

        try {
            RLock lock = redisson.getLock(prefix + key);
            boolean acquired = lock.tryLock(waitTime.toMillis(), leaseTime.toMillis(), TimeUnit.MILLISECONDS);

            if (!acquired) {
                throw new IllegalStateException("Failed to acquire Redisson lock: " + prefix + key);
            }

            return new RedissonLockHandle(lock, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while acquiring Redisson lock.", exception);
        }
    }

    private static String normalizePrefix(String prefix) {
        Objects.requireNonNull(prefix, "prefix");

        if (prefix.endsWith(":")) {
            return prefix;
        }

        return prefix + ":";
    }

    private record RedissonLockHandle(RLock lock, boolean acquired) implements LockHandle {

        @Override
        public boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            if (!acquired) {
                return;
            }

            if (!lock.isHeldByCurrentThread()) {
                return;
            }

            lock.unlock();
        }

    }

}