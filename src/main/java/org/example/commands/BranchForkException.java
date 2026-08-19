package org.example.commands;

public final class BranchForkException extends RuntimeException {
    public BranchForkException(String message) {
        super(message);
    }

    public BranchForkException(String message, Throwable cause) {
        super(message, cause);
    }
}
