package org.example.core.locking;

/** A held branch lock, released by {@link #close()}; no checked exception so try-with-resources covers the failure path too. */
@FunctionalInterface
public interface BranchLease extends AutoCloseable {
    @Override
    void close();
}
