package org.example.unit.core.locking;

import org.example.core.locking.BranchLocks;

import java.time.Duration;

/** In-memory locking with a generous timeout - for tests running a single daemon in one JVM. */
public final class TestBranchLocks {
    private TestBranchLocks() {
    }

    public static BranchLocks inMemory() {
        return new BranchLocks(new InMemoryBranchLock(), Duration.ofSeconds(60));
    }
}
