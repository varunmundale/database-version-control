package org.example.branch;

/** Details of a branch database in the shared PostgreSQL instance. */
public record BranchForkResult(String fromBranch, String currentBranch, String containerName, String databaseName) {
}
