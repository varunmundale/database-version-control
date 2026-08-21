package org.example.core;

import org.example.branch.BranchFork;
import org.example.connectors.SqlConnector;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.versioning.BranchMetadataStore;

import java.sql.SQLException;
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
 * introduced them, reachable by walking both parent chains ({@link BranchMetadataStore#commitHistory}).
 */
public final class BranchMerger {
    private final BranchFork branchFork;
    private final SchemaReplayer schemaReplayer;
    private final DatabaseDiff databaseDiff;
    private final DatabaseRecreator databaseRecreator;

    public BranchMerger(BranchFork branchFork, SchemaReplayer schemaReplayer) {
        this(branchFork, schemaReplayer, new DatabaseDiff(), new DatabaseRecreator());
    }

    public BranchMerger(BranchFork branchFork, SchemaReplayer schemaReplayer, DatabaseDiff databaseDiff, DatabaseRecreator databaseRecreator) {
        this.branchFork = Objects.requireNonNull(branchFork, "branchFork must not be null");
        this.schemaReplayer = Objects.requireNonNull(schemaReplayer, "schemaReplayer must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
        this.databaseRecreator = Objects.requireNonNull(databaseRecreator, "databaseRecreator must not be null");
    }

    public MergeResult merge(String currentBranch, String otherBranch) {
        BranchMetadataStore metadataStore = branchFork.metadataStore();
        List<ChangeSet> currentHistory = metadataStore.commitHistory(currentBranch);
        List<ChangeSet> otherHistory = metadataStore.commitHistory(otherBranch);
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
        branchFork.fork(currentBranch, stagingBranch);
        replay(branchFork.defaultDatabaseName(stagingBranch), otherOnly);

        replay(branchFork.defaultDatabaseName(currentBranch), otherOnly);

        long commitId = metadataStore.createMergeCommit(currentBranch, otherBranch);
        return new MergeResult.Success(commitId, stagingBranch, otherOnly.size());
    }

    private void replay(String database, List<ChangeSet> changesets) {
        try (SqlConnector connector = branchFork.connect(database)) {
            databaseRecreator.recreate(connector, changesets);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not replay merge changesets against database '" + database
                    + "': " + exception.getMessage(), exception);
        }
    }

    /** Every column {@link DatabaseDiff} finds genuinely conflicting (matched by stable id) between the two branches' fully replayed schemas. */
    private List<String> conflicts(List<ChangeSet> currentHistory, List<ChangeSet> otherHistory) {
        Map<String, TableModel> currentSchema = schemaReplayer.replay(currentHistory);
        Map<String, TableModel> otherSchema = schemaReplayer.replay(otherHistory);

        List<String> lines = new ArrayList<>();
        for (TableDiff tableDiff : databaseDiff.diff(currentSchema.values(), otherSchema.values())) {
            tableDiff.columnDiffs().stream()
                    .filter(columnDiff -> columnDiff.side() == Side.CONFLICT)
                    .forEach(columnDiff -> lines.add("table '" + tableDiff.tableName() + "', column '" + columnDiff.columnName() + "'"));
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
