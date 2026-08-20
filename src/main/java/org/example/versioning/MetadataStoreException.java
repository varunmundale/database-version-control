package org.example.versioning;

public final class MetadataStoreException extends RuntimeException {
    public MetadataStoreException(String message) {
        super(message);
    }

    public MetadataStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
