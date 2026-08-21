package org.example.core.merger;

import org.example.core.differ.DatabaseDiff;
import org.example.core.differ.Side;
import org.example.core.differ.TableDiff;
import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.replayer.Replayer;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
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
    private final Forker forker;
    private final Replayer replayer;
    private final DatabaseDiff databaseDiff;
    private final BranchConnections connections;

    public Merger(Forker forker, Replayer replayer, BranchConnections connections) {
        this(forker, replayer, connections, new DatabaseDiff());
    }

    public Merger(Forker forker, Replayer replayer, BranchConnections connections, DatabaseDiff databaseDiff) {
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
    }

    public MergeResult merge(String currentBranch, String otherBranch) {
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
        forker.fork(currentBranch, stagingBranch);
        forker.branchDatabases().replay(connections.forBranch(stagingBranch), otherOnly);

        forker.branchDatabases().replay(connections.forBranch(currentBranch), otherOnly);

        long commitId = versioningService.createMergeCommit(currentBranch, otherBranch);
        return new MergeResult.Success(commitId, stagingBranch, otherOnly.size());
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

    private static String stagingBranchName(String currentBranch, String otherBranch) {
        return "merge/" + currentBranch + "-" + otherBranch;
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
