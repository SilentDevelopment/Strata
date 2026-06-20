package io.github.silentdevelopment.strata.lock;


import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Lock acquisition contract for named resources.
 */
public interface LockManager {

    @NotNull CompletableFuture<LockHandle> acquire(@NotNull String key, @NotNull Duration waitTime, @NotNull Duration leaseTime);

}
