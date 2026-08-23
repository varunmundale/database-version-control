package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.versioning.ChangeSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What {@link Differ} found between two branches' commit histories: the changesets each side has and the other
 * doesn't, every table whose fully replayed schema differs, which side actually moved each of those differing
 * objects since the branches diverged ({@link SideChanges}), and - by {@link StableId} - which of those exclusive
 * statements touched each column, constraint or index. Everything a consumer of a diff needs and nothing about how
 * it is displayed: {@link HistoryDiffFormatter} renders this for {@code dbgit diff}, while
 * {@link org.example.core.merger.Merger} reads the same value for conflicts and for the changesets a merge has to
 * replay.
 *
 * <p>{@link #isEmpty()} is about divergence, not about tables: two histories can diverge and still net out to the
 * same schema (a column added and dropped again on one side), which is a diff with no tables in it rather than no
 * diff at all.
 */
public record HistoryDiff(List<ChangeSet> leftOnly, List<ChangeSet> rightOnly, List<TableDiff> tables,
                          SideChanges changes,
                          Map<StableId, List<ChangeSet>> leftStatements,
                          Map<StableId, List<ChangeSet>> rightStatements) {
    public HistoryDiff {
        Objects.requireNonNull(leftOnly, "leftOnly must not be null");
        Objects.requireNonNull(rightOnly, "rightOnly must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(changes, "changes must not be null");
        Objects.requireNonNull(leftStatements, "leftStatements must not be null");
        Objects.requireNonNull(rightStatements, "rightStatements must not be null");
    }

    /** Two histories that carry exactly the same commits - nothing to report on either side. */
    static HistoryDiff identical() {
        return new HistoryDiff(List.of(), List.of(), List.of(), new SideChanges(Set.of(), Set.of()),
                Map.of(), Map.of());
    }

    /**
     * True when both branches changed this object since they diverged, so a merge cannot pick a winner. An object
     * that differs but that only one branch touched is not this: it is a change the other branch has yet to
     * receive.
     */
    public boolean isConflicting(StableId id) {
        return changes.conflicting(id);
    }

    /** True when neither branch has a commit the other lacks. */
    public boolean isEmpty() {
        return leftOnly.isEmpty() && rightOnly.isEmpty();
    }

    /**
     * The left branch's own statements against the object with this id, oldest first - and none at all unless the
     * branch actually left the object somewhere new. A branch that retyped a column and then retyped it back has
     * run statements against it but changed nothing, and reporting those under a difference it is not responsible
     * for reads as though it were: the compensating statement that settled a conflict would still be shown as one
     * side of it.
     */
    public List<ChangeSet> leftStatements(StableId id) {
        return changes.changedLeft(id) ? leftStatements.getOrDefault(id, List.of()) : List.of();
    }

    /** The right branch's own statements against the object with this id, on the same terms. */
    public List<ChangeSet> rightStatements(StableId id) {
        return changes.changedRight(id) ? rightStatements.getOrDefault(id, List.of()) : List.of();
    }
}
