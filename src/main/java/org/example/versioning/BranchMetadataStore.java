package org.example.versioning;

import org.example.models.versioning.ChangeSet;

import java.util.List;

/** The source of truth for which branches exist and their changeset/commit history. */
public interface BranchMetadataStore {
    /** All known branches, {@code main} always included. */
    List<String> branches();

    /** Atomically claims a branch name. Returns {@code false} if the branch already existed. */
    boolean createBranch(String branchName, String forkedFrom);

    /** Stages a raw DDL statement for a branch with status PENDING. Returns the new changeset's id. */
    long stageChangeset(String branch, String ddl);

    /** Transitions a staged changeset from PENDING to APPLIED, once it has run successfully against the database. */
    void markApplied(long changesetId);

    /** Every changeset staged for a branch, in the order they were staged, regardless of status. */
    List<ChangeSet> changesetsForBranch(String branch);

    /** The branch's committed changesets, walked from the root commit to its current HEAD. */
    List<ChangeSet> commitHistory(String branch);

    /**
     * Folds the given APPLIED changesets into one new commit, chained after the branch's current HEAD commit
     * (updating both the new commit's backward pointer and the previous HEAD's forward pointer). Changesets not
     * currently APPLIED are silently skipped. Returns the new commit's id.
     */
    long commit(String branch, List<Long> changesetIds);

    /**
     * Creates a merge commit for {@code branch}: chains its current HEAD as the first parent and
     * {@code otherBranch}'s current HEAD as the second parent. Carries no changesets of its own - the changesets it
     * brings in stay attributed to the commits that originally introduced them, reachable by walking both parent
     * chains. Moves {@code branch}'s HEAD to the new commit. Returns the new commit's id.
     */
    long createMergeCommit(String branch, String otherBranch);
}
