package org.example.core.locking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Takes the branch locks one operation needs, all of them or none.
 *
 * <p>Multi-branch operations are why this exists rather than callers using {@link BranchLock} directly:
 * {@code merge} holds its target and its source, {@code checkout -b} holds the parent and the child. Two merges in
 * opposite directions would deadlock if each took its own branch first, so branches are always locked in
 * lexicographic order regardless of the order asked for, and released in reverse.
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
