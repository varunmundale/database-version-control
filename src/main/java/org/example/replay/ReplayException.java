package org.example.replay;

/** An internal replay failure - e.g. previously-valid, already-committed history failing to reapply. */
public final class ReplayException extends RuntimeException {
    public ReplayException(String message, Throwable cause) {
        super(message, cause);
    }
}
