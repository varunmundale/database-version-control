package org.example.core.locking;

import java.time.Duration;

/**
 * Serializes work on one branch. The lock is held for a whole operation rather than for a transaction, because
 * the operations that need it - staging DDL, committing, merging, forking, resetting - interleave metadata writes
 * with side effects that cannot be rolled back: live DDL, {@code CREATE}/{@code DROP DATABASE}, {@code docker}. A
 * transaction-scoped lock would already be released by the time those run.
 */
public interface BranchLock {
    /**
     * @throws LockTimeoutException if the branch is still held when {@code timeout} elapses - never blocks forever,
     *                              because the wire protocol has no way to cancel a request
     */
    BranchLease acquire(String branch, Duration timeout);
}
