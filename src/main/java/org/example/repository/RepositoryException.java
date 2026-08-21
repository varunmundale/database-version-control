package org.example.repository;

/**
 * Anything that goes wrong reaching a database: the metadata store, or a branch's own database in the scratchpad
 * container. A storage failure, never a rule the caller broke - those stay with the service that owns the rule.
 */
public final class RepositoryException extends RuntimeException {
    public RepositoryException(String message) {
        super(message);
    }

    public RepositoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
