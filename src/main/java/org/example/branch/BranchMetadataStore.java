package org.example.branch;

import java.util.List;

/** The source of truth for which branches exist and which databases each one owns. */
public interface BranchMetadataStore {
    /** All known branches, {@code main} always included. */
    List<String> branches();

    /** Atomically claims a branch name. Returns {@code false} if the branch already existed. */
    boolean createBranch(String branchName, String forkedFrom);

    List<BranchDatabase> databasesForBranch(String branch);

    void recordDatabases(String branch, List<BranchDatabase> databases);

    void recordChangeset(ChangeSet changeset);

    /** Raw DDL statements previously recorded for a branch, in the order they were applied. */
    List<String> changesetsForBranch(String branch);
}
