package org.example.core.merger;

import org.example.core.differ.DatabaseDiff;
import org.example.core.differ.Differ;
import org.example.core.differ.HistoryDiff;
import org.example.core.differ.TableDiff;
import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.request.RequestContext;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitEntry;
import org.example.models.versioning.CommitMetadata;
import org.example.core.versioning.VersioningService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Merges another branch's diverged history into a target branch. Asks {@link Differ} - the same entry point
 * {@code dbgit diff} goes through - what the two branches' histories look like against each other: which of the
 * other branch's changesets this one doesn't have, and whether anything the two branches' fully replayed schemas
 * share genuinely disagrees, matched by stable id. A merge that decided either of those its own way could
 * contradict the diff the user was shown just before running it.
 *
 * <p>Free of conflicts, stages the merge in a scratch branch forked from the target's committed history,
 * physically replays the other branch's diverged changesets against it, and - only once that succeeds for real -
 * applies the same DDL to the target's own database and records a merge commit with two parents. A merge commit
 * itself carries no changesets; the changesets it brings in stay attributed to the commits that originally
 * introduced them, reachable by walking both parent chains ({@link VersioningService#commitHistory}).
 */
public final class Merger {
    /** Distinguishes one merge attempt from another, so their staging branches cannot collide. */
    private static final java.util.concurrent.atomic.AtomicLong NONCE =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    private final Forker forker;
    private final Differ differ;
    private final BranchConnections connections;
    private final BranchLocks locks;

    public Merger(Forker forker, Replayer replayer, BranchConnections connections, BranchLocks locks) {
        this(forker, connections, locks, new Differ(replayer, new DatabaseDiff()));
    }

    public Merger(Forker forker, BranchConnections connections, BranchLocks locks, Differ differ) {
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.differ = Objects.requireNonNull(differ, "differ must not be null");
    }

    /**
     * Both branches are locked, in a fixed order, for the whole merge: the conflict check, the replay into two
     * real databases and the merge commit are otherwise three views of a history that can move underneath them,
     * and a merge commit whose parents were never the basis for what was replayed is corruption no later read
     * can detect.
     */
    public MergeResult merge(RequestContext request, String otherBranch) {
        try (BranchLease ignored = locks.acquire(request.branch(), otherBranch)) {
            return merged(request, otherBranch);
        }
    }

    private MergeResult merged(RequestContext request, String otherBranch) {
        String currentBranch = request.branch();
        VersioningService versioningService = forker.versioningService();
        List<CommitEntry> currentCommits = versioningService.commits(currentBranch);
        List<CommitEntry> otherCommits = versioningService.commits(otherBranch);
        HistoryDiff diff = differ.diff(currentCommits, otherCommits);
        List<ChangeSet> otherOnly = diff.rightOnly();

        if (otherOnly.isEmpty()) {
            return new MergeResult.AlreadyUpToDate();
        }

        List<String> conflicts = conflicts(diff);
        if (!conflicts.isEmpty()) {
            return new MergeResult.Conflict(conflicts);
        }

        String stagingBranch = stagingBranchName(currentBranch, otherBranch);
        try {
            forker.fork(currentBranch, stagingBranch);
            forker.branchDatabases().replay(connections.forBranch(request, stagingBranch), otherOnly);

            forker.branchDatabases().replay(connections.forBranch(request, currentBranch), otherOnly);

            long commitId = versioningService.createMergeCommit(currentBranch, otherBranch,
                    new CommitMetadata(request.author(), "Merge branch '" + otherBranch + "' into '" + currentBranch + "'"));
            return new MergeResult.Success(commitId, stagingBranch, otherOnly.size());
        } finally {
            discard(stagingBranch);
        }
    }

    /**
     * Everything {@link Differ} found genuinely conflicting between the two branches: a table, column, constraint
     * or index both of them changed since they diverged, matched by stable id. A difference only one branch is
     * responsible for is not here - that is exactly what the merge is bringing in. Named for the user rather than
     * rendered as a tree: a merge reports why it stopped, it does not draw the diff.
     *
     * <p>A table-level conflict - both branches created, dropped or renamed the same table - is reported on its
     * own, before its members: a table one side dropped and the other renamed has no members left to compare, so
     * without this line such a conflict would otherwise report nothing at all and the merge would proceed onto
     * whichever side happened to run second.
     */
    private static List<String> conflicts(HistoryDiff diff) {
        List<String> lines = new ArrayList<>();
        for (TableDiff tableDiff : diff.tables()) {
            if (diff.isConflicting(tableDiff.id())) {
                lines.add("table '" + tableDiff.tableName() + "'");
            }
            String table = "table '" + tableDiff.tableName() + "', ";
            tableDiff.columnDiffs().stream()
                    .filter(columnDiff -> diff.isConflicting(columnDiff.id()))
                    .forEach(columnDiff -> lines.add(table + "column '" + columnDiff.columnName() + "'"));
            tableDiff.constraintDiffs().stream()
                    .filter(constraintDiff -> diff.isConflicting(constraintDiff.id()))
                    .forEach(constraintDiff -> lines.add(table + "constraint '" + constraintDiff.constraintName() + "'"));
            tableDiff.indexDiffs().stream()
                    .filter(indexDiff -> diff.isConflicting(indexDiff.id()))
                    .forEach(indexDiff -> lines.add(table + "index '" + indexDiff.indexName() + "'"));
        }
        return lines;
    }

    /**
     * The staging branch exists only to prove the replay works before it touches the target's real database, so it
     * goes as soon as that is settled - whether the merge succeeded or not. Best-effort: failing to clean up must
     * not turn a completed merge into a reported failure.
     */
    private void discard(String stagingBranch) {
        try {
            forker.branchDatabases().dropDatabase(BranchConnections.forkedDatabaseName(stagingBranch));
        } catch (RuntimeException ignored) {
            // Left behind in the scratchpad; harmless, and the name will not be reused.
        }
        try {
            forker.versioningService().deleteBranch(stagingBranch);
        } catch (RuntimeException ignored) {
            // Ditto - it holds no changesets of its own.
        }
    }

    private static String stagingBranchName(String currentBranch, String otherBranch) {
        // Unique per attempt. The name used to be derived from the two branches alone, which was fine only while
        // one merge could ever be in flight: two overlapping merges of the same pair - or one retried after a
        // failure - collided on a branch that already existed, and the second was refused for the wrong reason.
        return "merge/" + currentBranch + "-" + otherBranch + "-" + Long.toHexString(NONCE.incrementAndGet());
    }
}
