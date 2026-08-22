package org.example.client;

/** A local {@code .dbgit} file could not be read or written. Unchecked, so no IO plumbing reaches the CLI. */
public final class WorkspaceException extends RuntimeException {
    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
