package org.example.core.locking;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes branches within one JVM. Correct only while exactly one daemon process is talking to a given metadata
 * store - two would each serialize their own callers and happily corrupt each other - so this is the test double,
 * and {@link AdvisoryBranchLock} is what the daemon runs.
 *
 * <p>Fair, so a branch under sustained load cannot starve one waiter indefinitely, and reentrant, so an operation
 * that ends up asking for a branch it already holds proceeds rather than deadlocking against itself.
 */
public final class InMemoryBranchLock implements BranchLock {
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public BranchLease acquire(String branch, Duration timeout) {
        ReentrantLock lock = locks.computeIfAbsent(branch, name -> new ReentrantLock(true));
        try {
            if (!lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new LockTimeoutException(branch, timeout);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(branch, exception);
        }
        return lock::unlock;
    }
}
