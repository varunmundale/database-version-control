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
 * Merges another branch's diverged history into a target branch, via the same {@link Differ} {@code dbgit diff}
 * uses, so a merge can't disagree with the diff the user was just shown. Conflict-free changes are replayed first
 * against a scratch staging branch and only applied to the target once that succeeds for real.
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

    /** Both branches are locked, in a fixed order, for the whole merge, so history can't move underneath it. */
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
     * Every table/column/constraint/index both branches changed since they diverged, as user-facing lines rather
     * than a diff tree. A table-level conflict is reported on its own since a dropped-vs-renamed table has no
     * members left to compare.
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

    /** Best-effort cleanup: failing to remove the staging branch must not turn a completed merge into a failure. */
    private void discard(String stagingBranch) {
        try {
            forker.branchDatabases().dropDatabase(BranchConnections.forkedDatabaseName(stagingBranch));
        } catch (RuntimeException ignored) {
            // harmless; the name will not be reused
        }
        try {
            forker.versioningService().deleteBranch(stagingBranch);
        } catch (RuntimeException ignored) {
            // ditto
        }
    }

    private static String stagingBranchName(String currentBranch, String otherBranch) {
        return "merge/" + currentBranch + "-" + otherBranch + "-" + Long.toHexString(NONCE.incrementAndGet());
    }
}
