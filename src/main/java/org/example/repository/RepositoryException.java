package org.example.repository;

/** A storage failure reaching the metadata store or a branch database - never a rule the caller broke. */
public final class RepositoryException extends RuntimeException {
    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
