package org.example.core.merger;

import org.example.core.differ.DatabaseDiff;
import org.example.core.differ.Side;
import org.example.core.differ.TableDiff;
import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.protocol.RequestContext;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitMetadata;
import org.example.core.versioning.VersioningService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merges another branch's diverged history into a target branch. Reuses {@link DatabaseDiff} (the same engine
 * {@code dbgit diff} renders) to detect genuine conflicts between the two branches' fully replayed schemas, matched
 * by stable id. Free of conflicts, stages the merge in a scratch branch forked from the target's committed history,
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
    private final Replayer replayer;
    private final DatabaseDiff databaseDiff;
    private final BranchConnections connections;
    private final BranchLocks locks;

    public Merger(Forker forker, Replayer replayer, BranchConnections connections, BranchLocks locks) {
        this(forker, replayer, connections, locks, new DatabaseDiff());
    }

    public Merger(Forker forker, Replayer replayer, BranchConnections connections, BranchLocks locks,
                  DatabaseDiff databaseDiff) {
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
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
        List<ChangeSet> currentHistory = versioningService.commitHistory(currentBranch);
        List<ChangeSet> otherHistory = versioningService.commitHistory(otherBranch);
        int divergedAt = commonPrefixLength(currentHistory, otherHistory);
        List<ChangeSet> otherOnly = otherHistory.subList(divergedAt, otherHistory.size());

        if (otherOnly.isEmpty()) {
            return new MergeResult.AlreadyUpToDate();
        }

        List<String> conflicts = conflicts(currentHistory, otherHistory);
        if (!conflicts.isEmpty()) {
            return new MergeResult.Conflict(conflicts);
        }

        String stagingBranch = stagingBranchName(currentBranch, otherBranch);
        try {
            forker.fork(currentBranch, stagingBranch);
            forker.branchDatabases().replay(connections.forBranch(request, stagingBranch), otherOnly);

            forker.branchDatabases().replay(connections.forBranch(request, currentBranch), otherOnly);

            long commitId = versioningService.createMergeCommit(currentBranch, otherBranch,
                    CommitMetadata.by(null, "Merge branch '" + otherBranch + "' into '" + currentBranch + "'"));
            return new MergeResult.Success(commitId, stagingBranch, otherOnly.size());
        } finally {
            discard(stagingBranch);
        }
    }

    /**
     * Everything {@link DatabaseDiff} finds genuinely conflicting (matched by stable id) between the two branches'
     * fully replayed schemas: a column, constraint or index both branches changed in incompatible ways.
     */
    private List<String> conflicts(List<ChangeSet> currentHistory, List<ChangeSet> otherHistory) {
        Map<String, TableModel> currentSchema = replayer.replay(currentHistory);
        Map<String, TableModel> otherSchema = replayer.replay(otherHistory);

        List<String> lines = new ArrayList<>();
        for (TableDiff tableDiff : databaseDiff.diff(currentSchema.values(), otherSchema.values())) {
            String table = "table '" + tableDiff.tableName() + "', ";
            tableDiff.columnDiffs().stream()
                    .filter(columnDiff -> columnDiff.side() == Side.CONFLICT)
                    .forEach(columnDiff -> lines.add(table + "column '" + columnDiff.columnName() + "'"));
            tableDiff.constraintDiffs().stream()
                    .filter(constraintDiff -> constraintDiff.side() == Side.CONFLICT)
                    .forEach(constraintDiff -> lines.add(table + "constraint '" + constraintDiff.constraintName() + "'"));
            tableDiff.indexDiffs().stream()
                    .filter(indexDiff -> indexDiff.side() == Side.CONFLICT)
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

    /** How far the two histories agree before diverging - the same notion {@link HistoryDiffFormatter} uses. */
    private static int commonPrefixLength(List<ChangeSet> left, List<ChangeSet> right) {
        int length = Math.min(left.size(), right.size());
        int common = 0;
        while (common < length && left.get(common).id() == right.get(common).id()) {
            common++;
        }
        return common;
    }
}
