package org.example.core.versioning;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.models.tracking.TrackedDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The versioning API the rest of dbgit works against: the source of truth for which branches exist and how each
 * one's changeset/commit history is put together. Implementations own the storage; callers never see one.
 */
public interface VersioningService {
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

    /**
     * Records which physical database a branch's DDL is applied to, replacing any previous record for that branch.
     * Credential-free by construction - see {@link TrackedDatabase}. Returns what is now tracked.
     */
    TrackedDatabase track(String branch, String host, int port, String database, String user);

    /** What the branch tracks, or empty if it has never been initialised. */
    Optional<TrackedDatabase> trackedDatabase(String branch);

    /** The branch's changesets that have run against its database but aren't part of a commit yet. */
    default List<ChangeSet> appliedChangesets(String branch) {
        return changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .toList();
    }

    /**
     * The changesets that make up a branch's current schema, in order: its inherited/committed history (reached via
     * the shared commit chain, regardless of which branch originally created each commit) followed by its own
     * not-yet-committed applied changesets.
     */
    default List<ChangeSet> branchHistory(String branch) {
        List<ChangeSet> history = new ArrayList<>(commitHistory(branch));
        history.addAll(appliedChangesets(branch));
        return history;
    }
}
