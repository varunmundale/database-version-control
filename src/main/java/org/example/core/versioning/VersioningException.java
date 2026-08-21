package org.example.core.versioning;

/** Anything that goes wrong reading or writing branch, changeset and commit metadata. */
public final class VersioningException extends RuntimeException {
    public VersioningException(String message) {
        super(message);
    }

    public VersioningException(String message, Throwable cause) {
        super(message, cause);
    }
}
