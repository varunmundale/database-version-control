package org.example.branch;

import java.util.List;

/** Details of a branch fork: the databases forked from the parent branch into the shared PostgreSQL instance. */
public record BranchForkResult(String fromBranch, String currentBranch, String containerName, List<String> databaseNames) {
}
