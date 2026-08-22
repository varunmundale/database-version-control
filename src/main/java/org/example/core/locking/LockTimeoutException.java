package org.example.core.locking;

import java.time.Duration;

/** A branch was busy for longer than the caller was willing to wait. */
public final class LockTimeoutException extends RuntimeException {
    public LockTimeoutException(String branch, Duration waited) {
        super("Branch '" + branch + "' is busy: another command has been holding it for more than "
                + waited.toSeconds() + "s. Try again shortly.");
    }

    public LockTimeoutException(String branch, Throwable cause) {
        super("Interrupted while waiting for branch '" + branch + "'.", cause);
    }
}
