package org.example.unit.core.locking;


import org.example.core.locking.AdvisoryBranchLock;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLock;
import org.example.core.locking.BranchLocks;
import org.example.core.locking.InMemoryBranchLock;
import org.example.core.locking.LockTimeoutException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchLocksTest {
    private static final Duration TIMEOUT = Duration.ofMillis(200);

    @Test
    void aBranchIsHeldUntilTheLeaseIsClosed() throws Exception {
        BranchLocks locks = new BranchLocks(new InMemoryBranchLock(), TIMEOUT);
        CountDownLatch second = new CountDownLatch(1);

        try (BranchLease ignored = locks.acquire("main")) {
            Thread other = new Thread(() -> {
                try (BranchLease alsoIgnored = locks.acquire("main")) {
                    second.countDown();
                } catch (LockTimeoutException expected) {
                    // the point of the test
                }
            });
            other.start();
            assertTrue(!second.await(400, TimeUnit.MILLISECONDS), "the branch should still be held");
            other.join();
        }

        // Once released it is available again.
        try (BranchLease ignored = locks.acquire("main")) {
            assertTrue(true);
        }
    }

    @Test
    void aBranchThatStaysBusyRaisesRatherThanBlockingForever() {
        BranchLocks locks = new BranchLocks(new InMemoryBranchLock(), Duration.ofMillis(50));

        try (BranchLease ignored = locks.acquire("feature/orders")) {
            LockTimeoutException exception = assertThrows(LockTimeoutException.class,
                    () -> inAnotherThread(() -> locks.acquire("feature/orders")));

            assertTrue(exception.getMessage().contains("feature/orders"), exception.getMessage());
            assertTrue(exception.getMessage().contains("busy"), exception.getMessage());
        }
    }

    /**
     * The deadlock this prevents: {@code merge a into b} and {@code merge b into a} arriving together, each
     * taking its own branch first and then waiting forever for the other's.
     */
    @Test
    void branchesAreAlwaysTakenInTheSameOrderHoweverTheyAreAsked() {
        List<String> acquired = new ArrayList<>();
        BranchLocks locks = new BranchLocks(recording(acquired), TIMEOUT);

        try (BranchLease ignored = locks.acquire("main", "feature/orders")) {
            assertEquals(List.of("feature/orders", "main"), acquired);
        }

        acquired.clear();
        try (BranchLease ignored = locks.acquire("feature/orders", "main")) {
            assertEquals(List.of("feature/orders", "main"), acquired);
        }
    }

    @Test
    void leasesAreReleasedInReverseOrder() {
        List<String> released = new ArrayList<>();
        BranchLocks locks = new BranchLocks(
                (branch, timeout) -> () -> released.add(branch), TIMEOUT);

        locks.acquire("main", "feature/orders", "hotfix").close();

        assertEquals(List.of("main", "hotfix", "feature/orders"), released);
    }

    /** A partly-taken set must not be left held, or the branches it did get are stuck until the daemon restarts. */
    @Test
    void aFailureMidwayReleasesWhateverWasAlreadyTaken() {
        List<String> released = new ArrayList<>();
        BranchLocks locks = new BranchLocks((branch, timeout) -> {
            if (branch.equals("main")) {
                throw new LockTimeoutException(branch, timeout);
            }
            return () -> released.add(branch);
        }, TIMEOUT);

        assertThrows(LockTimeoutException.class, () -> locks.acquire("main", "feature/orders"));

        assertEquals(List.of("feature/orders"), released, "the branch already taken should have been given back");
    }

    @Test
    void theSameBranchNamedTwiceIsTakenOnce() {
        List<String> acquired = new ArrayList<>();
        BranchLocks locks = new BranchLocks(recording(acquired), TIMEOUT);

        try (BranchLease ignored = locks.acquire("main", "main")) {
            assertEquals(List.of("main"), acquired);
        }
    }

    /** Keys must not depend on anything process-local, or two daemons would lock different things. */
    @Test
    void advisoryLockKeysAreDerivedFromTheBranchNameAlone() {
        assertEquals(AdvisoryBranchLock.keyFor("feature/orders"), AdvisoryBranchLock.keyFor("feature/orders"));
        assertTrue(AdvisoryBranchLock.keyFor("main") != AdvisoryBranchLock.keyFor("feature/orders"));
    }

    private static BranchLock recording(List<String> acquired) {
        return (branch, timeout) -> {
            acquired.add(branch);
            return () -> {
            };
        };
    }

    private static void inAnotherThread(Runnable work) throws Throwable {
        List<Throwable> failure = Collections.synchronizedList(new ArrayList<>());
        Thread thread = new Thread(() -> {
            try {
                work.run();
            } catch (Throwable caught) {
                failure.add(caught);
            }
        });
        thread.start();
        thread.join();
        if (!failure.isEmpty()) {
            throw failure.getFirst();
        }
    }
}
