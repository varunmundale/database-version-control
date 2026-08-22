package org.example.core.locking;

/**
 * A held branch lock, released by {@link #close()}. Declared {@code AutoCloseable} with no checked exception so a
 * caller can hold one across an entire operation with try-with-resources and not have to reason about unlocking
 * on the failure path - which is the path that matters, since the side effects a lock protects cannot be rolled
 * back.
 */
@FunctionalInterface
public interface BranchLease extends AutoCloseable {
    @Override
    void close();
}
