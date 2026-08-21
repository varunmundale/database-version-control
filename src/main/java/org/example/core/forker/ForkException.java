package org.example.core.forker;

public final class ForkException extends RuntimeException {
    public ForkException(String message) {
        super(message);
    }

    public ForkException(String message, Throwable cause) {
        super(message, cause);
    }
}
