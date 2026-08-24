package org.example.core.locking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Takes the branch locks one operation needs, all of them or none. Always locked in lexicographic order and
 * released in reverse, so two merges in opposite directions can't deadlock each other.
 */
public final class BranchLocks {
    private final BranchLock lock;
    private final Duration timeout;

    public BranchLocks(BranchLock lock, Duration timeout) {
        this.lock = Objects.requireNonNull(lock, "lock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    /**
     * @throws LockTimeoutException if any branch stays busy past the timeout; anything already taken is released
     */
    public BranchLease acquire(String... branches) {
        List<String> ordered = Arrays.stream(branches).filter(Objects::nonNull).distinct().sorted().toList();
        List<BranchLease> held = new ArrayList<>(ordered.size());
        try {
            for (String branch : ordered) {
                held.add(lock.acquire(branch, timeout));
            }
        } catch (RuntimeException exception) {
            releaseInReverse(held);
            throw exception;
        }
        return () -> releaseInReverse(held);
    }

    private static void releaseInReverse(List<BranchLease> held) {
        for (int index = held.size() - 1; index >= 0; index--) {
            held.get(index).close();
        }
    }
}
