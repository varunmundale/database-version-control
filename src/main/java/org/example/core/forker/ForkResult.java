package org.example.core.forker;

/** Details of a branch fork: its own database, recreated from the parent branch's commit history. */
public record ForkResult(String fromBranch, String currentBranch, String containerName, String databaseName) {
}
