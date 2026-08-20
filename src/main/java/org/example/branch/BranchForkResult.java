package org.example.branch;

/** Details of a branch fork: its own database, recreated from the parent branch's commit history. */
public record BranchForkResult(String fromBranch, String currentBranch, String containerName, String databaseName) {
}
