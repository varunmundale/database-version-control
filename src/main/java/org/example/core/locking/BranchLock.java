package org.example.core.locking;

import java.time.Duration;

/**
 * Serializes work on one branch, held for a whole operation rather than one transaction, since these operations
 * interleave metadata writes with side effects (live DDL, {@code docker}) that a transaction-scoped lock would
 * already have released by the time they run.
 */
public interface BranchLock {
    /**
     * @throws LockTimeoutException if the branch is still held when {@code timeout} elapses - never blocks forever,
     *                              because the wire protocol has no way to cancel a request
     */
    BranchLease acquire(String branch, Duration timeout);
}
