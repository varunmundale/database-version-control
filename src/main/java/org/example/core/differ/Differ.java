package org.example.core.differ;

import org.example.core.replayer.Replayer;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitEntry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The one way two branches are compared: {@code dbgit diff} and {@link org.example.core.merger.Merger} both enter
 * here, so they always get the same answer. Divergence is a set difference by commit id, never a positional
 * prefix - a shared commit can sit at different indexes in each branch's flattened history once either has been
 * the target of a merge. Differences are judged against a third schema, the one the branches shared when they
 * diverged, which is what tells a genuine conflict from a change one side just hasn't received yet
 * ({@link SideChanges}). Attribution (which statement touched which object) replays history one statement at a
 * time and asks {@link TableDiff#between} what changed, for changesets exclusive to a side only.
 */
public final class Differ {
    private final Replayer replayer;
    private final DatabaseDiff databaseDiff;

    public Differ() {
        this(new Replayer(), new DatabaseDiff());
    }

    public Differ(Replayer replayer, DatabaseDiff databaseDiff) {
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
    }

    /** Compares two branches by their commit histories, newest state against newest state. */
    public HistoryDiff diff(List<CommitEntry> leftCommits, List<CommitEntry> rightCommits) {
        List<ChangeSet> leftOnly = exclusiveChangesets(leftCommits, rightCommits);
        List<ChangeSet> rightOnly = exclusiveChangesets(rightCommits, leftCommits);
        if (leftOnly.isEmpty() && rightOnly.isEmpty()) {
            return HistoryDiff.identical();
        }

        List<ChangeSet> leftHistory = changesets(leftCommits);
        List<ChangeSet> rightHistory = changesets(rightCommits);
        List<TableDiff> tables = databaseSnapshotDiff(leftHistory, rightHistory);
        Map<String, TableModel> base = replayer.replay(sharedChangesets(leftCommits, rightCommits));

        return new HistoryDiff(leftOnly, rightOnly, tables, SideChanges.since(base, tables),
                attribute(leftHistory, idsOf(leftOnly)), attribute(rightHistory, idsOf(rightOnly)));
    }

    /** The commits both branches carry - the schema they diverged from. Either branch's order replays the same schema. */
    private static List<ChangeSet> sharedChangesets(List<CommitEntry> left, List<CommitEntry> right) {
        Set<Long> rightIds = right.stream().map(CommitEntry::commitId).collect(Collectors.toSet());
        return left.stream()
                .filter(entry -> rightIds.contains(entry.commitId()))
                .flatMap(entry -> entry.changesets().stream())
                .toList();
    }

    /** Every table that differs between the two fully replayed histories, ordered by table name. */
    private List<TableDiff> databaseSnapshotDiff(List<ChangeSet> leftHistory, List<ChangeSet> rightHistory) {
        Map<String, TableModel> leftSchema = replayer.replay(leftHistory);
        Map<String, TableModel> rightSchema = replayer.replay(rightHistory);
        return databaseDiff.diff(leftSchema.values(), rightSchema.values());
    }

    /** Every changeset carried by one of {@code commits}, in {@code commits}' own order. */
    private static List<ChangeSet> changesets(List<CommitEntry> commits) {
        return commits.stream().flatMap(entry -> entry.changesets().stream()).toList();
    }

    /**
     * {@code from}'s commits that {@code excluding} doesn't have, flattened to the changesets they carry, in
     * {@code from}'s own order.
     */
    private static List<ChangeSet> exclusiveChangesets(List<CommitEntry> from, List<CommitEntry> excluding) {
        Set<Long> excludingIds = excluding.stream().map(CommitEntry::commitId).collect(Collectors.toSet());
        return from.stream()
                .filter(entry -> !excludingIds.contains(entry.commitId()))
                .flatMap(entry -> entry.changesets().stream())
                .toList();
    }

    /** Walks history a statement at a time, recording - for changesets in {@code onlyIds} - every object each changed. */
    private Map<StableId, List<ChangeSet>> attribute(List<ChangeSet> history, Set<Long> onlyIds) {
        Map<String, TableModel> tables = new LinkedHashMap<>();
        Map<StableId, List<ChangeSet>> byObject = new LinkedHashMap<>();
        for (ChangeSet changeset : history) {
            String table = replayer.tableName(changeset.ddl());
            TableModel before = tables.get(table);
            TableModel after = replayer.apply(tables, changeset.ddl());
            if (onlyIds.contains(changeset.id())) {
                recordTouched(before, after, changeset, byObject);
            }
        }
        return byObject;
    }

    private static void recordTouched(TableModel before, TableModel after, ChangeSet changeset,
                                      Map<StableId, List<ChangeSet>> byObject) {
        TableDiff delta = TableDiff.between(before, after);
        if (delta.tableChanged()) {
            record(byObject, delta.id(), changeset);
        }
        delta.columnDiffs().forEach(columnDiff -> record(byObject, columnDiff.id(), changeset));
        delta.constraintDiffs().forEach(constraintDiff -> record(byObject, constraintDiff.id(), changeset));
        delta.indexDiffs().forEach(indexDiff -> record(byObject, indexDiff.id(), changeset));
    }

    private static void record(Map<StableId, List<ChangeSet>> byObject, StableId id, ChangeSet changeset) {
        byObject.computeIfAbsent(id, unused -> new ArrayList<>()).add(changeset);
    }

    private static Set<Long> idsOf(List<ChangeSet> changesets) {
        return changesets.stream().map(ChangeSet::id).collect(Collectors.toSet());
    }
}
