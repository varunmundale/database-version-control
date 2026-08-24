package org.example.core.versioning;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.models.versioning.CommitEntry;
import org.example.models.versioning.CommitMetadata;
import org.example.config.ConnectionSettings;
import org.example.config.TrackedDatabaseConfig;

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

    /** Gives up a branch name whose database was never finished; only safe if it has staged nothing yet. */
    void deleteBranch(String branch);

    /** Stages a raw DDL statement for a branch with status PENDING. Returns the new changeset's id. */
    long stageChangeset(String branch, String ddl);

    /** Throws away a staged changeset whose DDL never made it into the database. */
    void discardChangeset(long changesetId);

    /** Transitions a staged changeset from PENDING to APPLIED, once it has run successfully against the database. */
    void markApplied(long changesetId);

    /** Every changeset staged for a branch, in the order they were staged, regardless of status. */
    List<ChangeSet> changesetsForBranch(String branch);

    /**
     * The branch's commits, root to HEAD, each with its changesets folded in - the one ancestry walk the rest of a
     * branch's history is derived from ({@link #commitHistory}, {@code dbgit log}/{@code reset}).
     */
    List<CommitEntry> commits(String branch);

    /** Folds the given APPLIED changesets into one new commit chained after the branch's HEAD. Returns its id. */
    long commit(String branch, List<Long> changesetIds, CommitMetadata metadata);

    /**
     * Creates a merge commit for {@code branch}, chaining its HEAD and {@code otherBranch}'s HEAD as its two
     * parents; carries no changesets of its own. Moves {@code branch}'s HEAD to the new commit. Returns its id.
     */
    long createMergeCommit(String branch, String otherBranch, CommitMetadata metadata);

    /**
     * Moves a branch's HEAD back to one of its own ancestor commits and discards its working set. Later commits
     * stay in the shared graph, just unreachable from this branch. Returns how many working changesets were dropped.
     */
    int resetTo(String branch, long commitId);

    /** Records which physical database a branch's DDL is applied to, replacing any previous record. */
    TrackedDatabaseConfig track(String branch, ConnectionSettings settings);

    /** What the branch tracks, or empty if it has never been initialised. */
    Optional<TrackedDatabaseConfig> trackedDatabase(String branch);

    /** Rejects a branch name that isn't one of {@link #branches()}, with the message every command reports it as. */
    default void requireBranchExists(String branch) {
        if (!branches().contains(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
    }

    /** The branch's committed changesets, walked from the root commit to its current HEAD. */
    default List<ChangeSet> commitHistory(String branch) {
        return commits(branch).stream().flatMap(entry -> entry.changesets().stream()).toList();
    }

    /**
     * Everything staged on a branch that no commit has claimed yet, in staged order. Wider than
     * {@link #appliedChangesets}: a changeset still PENDING (its DDL failed) is still part of what's lying around.
     */
    default List<ChangeSet> workingSet(String branch) {
        return changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() != ChangesetStatus.COMMIT)
                .toList();
    }

    /** The branch's changesets that have run against its database but aren't part of a commit yet. */
    default List<ChangeSet> appliedChangesets(String branch) {
        return changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .toList();
    }

    /** The changesets making up a branch's current schema: committed history, then its own applied-not-yet-committed ones. */
    default List<ChangeSet> branchHistory(String branch) {
        List<ChangeSet> history = new ArrayList<>(commitHistory(branch));
        history.addAll(appliedChangesets(branch));
        return history;
    }
}
