package org.example.branch;

/** One database tracked for a branch: its branch-scoped logical name and its physical PostgreSQL database name. */
public record BranchDatabase(String logicalName, String databaseName) {
}
