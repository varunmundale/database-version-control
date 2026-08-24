package org.example.core.stager;

/**
 * What one DDL statement staged and applied via {@link Stager} left behind - a table to describe, or nothing at
 * all. Sealed the way {@link org.example.core.merger.MergeResult} is, so {@code AddCommand} handles both without a
 * null check standing in for the second case.
 */
public sealed interface StageResult {
    long changesetId();

    /** The statement left a table behind - created, altered, or renamed (under its new name, {@code tableName}). */
    record Applied(long changesetId, String tableName, int columnCount) implements StageResult {
    }

    /** The statement dropped the table it named; there is nothing left to describe. */
    record Dropped(long changesetId, String tableName) implements StageResult {
    }
}
