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
 * The one way two branches are compared. {@code dbgit diff} enters here and so does
 * {@link org.example.core.merger.Merger} - a merge asks the same question a diff does (what does the other branch
 * have that this one doesn't, and does anything genuinely disagree), so it must get the same answer, computed the
 * same way. Everything else in this package is a collaborator: {@link Replayer} turns a history into schema,
 * {@link DatabaseDiff}/{@link TableDiff} compare two schemas, {@link HistoryDiff} is the result and
 * {@link HistoryDiffFormatter} renders it.
 *
 * <p>Divergence is a set difference by commit id, never a positional prefix: a commit id is unique and stable
 * regardless of where in a branch's own flattened history it turns up, and once either branch has been the target
 * of a merge, a shared commit sits at different indexes on the two sides - a merge commit's second-parent
 * contributions sort after the first parent's own unique commits. Comparing positions replayed already-shared
 * changesets a second time.
 *
 * <p>Attribution - which statement touched which column, constraint or index - is worked out by replaying the
 * history one statement at a time and asking {@link TableDiff#between} what that statement changed about its
 * table, so it uses the same matching-by-stable-id a whole-schema diff does. Only the changesets exclusive to a
 * side are attributed; one both branches already share has nothing to report.
 */
public final class Differ {
    private static final String SCHEMA = "public";

    private final Replayer replayer;
    private final DatabaseDiff databaseDiff;

    public Differ() {
        this(new Replayer(), new DatabaseDiff());
    }

    public Differ(Replayer replayer, DatabaseDiff databaseDiff) {
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
    }

    /**
     * Compares two branches by their commit histories, newest state against newest state: what each side alone
     * carries, which tables actually differ once both histories are replayed in full, and which of those exclusive
     * statements touched each object.
     */
    public HistoryDiff diff(List<CommitEntry> leftCommits, List<CommitEntry> rightCommits) {
        List<ChangeSet> leftOnly = exclusiveChangesets(leftCommits, rightCommits);
        List<ChangeSet> rightOnly = exclusiveChangesets(rightCommits, leftCommits);
        if (leftOnly.isEmpty() && rightOnly.isEmpty()) {
            return HistoryDiff.identical();
        }

        List<ChangeSet> leftHistory = changesets(leftCommits);
        List<ChangeSet> rightHistory = changesets(rightCommits);
        return new HistoryDiff(leftOnly, rightOnly, databaseSnapshotDiff(leftHistory, rightHistory),
                attribute(leftHistory, idsOf(leftOnly)), attribute(rightHistory, idsOf(rightOnly)));
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

    /**
     * Walks one branch's whole history a statement at a time - a statement can only be read against the table as
     * it stood just before it - recording, for the changesets in {@code onlyIds}, every object that statement
     * changed.
     */
    private Map<StableId, List<ChangeSet>> attribute(List<ChangeSet> history, Set<Long> onlyIds) {
        Map<String, TableModel> tables = new LinkedHashMap<>();
        Map<StableId, List<ChangeSet>> byObject = new LinkedHashMap<>();
        for (ChangeSet changeset : history) {
            String table = replayer.tableName(changeset.ddl());
            TableModel before = tables.get(table);
            TableModel after = replayer.apply(SCHEMA, changeset.ddl(), before);
            tables.put(table, after);
            if (onlyIds.contains(changeset.id())) {
                recordTouched(before, after, changeset, byObject);
            }
        }
        return byObject;
    }

    private static void recordTouched(TableModel before, TableModel after, ChangeSet changeset,
                                      Map<StableId, List<ChangeSet>> byObject) {
        TableDiff delta = TableDiff.between(after.name(), before, after);
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
