package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.versioning.ChangeSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What {@link Differ} found between two branches' histories: the changesets exclusive to each side, every table
 * whose replayed schema differs, which side actually moved each differing object ({@link SideChanges}), and which
 * exclusive statements touched each column/constraint/index. {@link HistoryDiffFormatter} renders this for
 * {@code dbgit diff}; {@link org.example.core.merger.Merger} reads it for conflicts and what to replay.
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

    /** True when both branches changed this object since they diverged, so a merge cannot pick a winner. */
    public boolean isConflicting(StableId id) {
        return changes.conflicting(id);
    }

    /** True when neither branch has a commit the other lacks. */
    public boolean isEmpty() {
        return leftOnly.isEmpty() && rightOnly.isEmpty();
    }

    /**
     * The left branch's own statements against this object, oldest first - empty unless the branch actually left
     * the object somewhere new (a retype followed by a compensating retype back reports nothing).
     */
    public List<ChangeSet> leftStatements(StableId id) {
        return changes.changedLeft(id) ? leftStatements.getOrDefault(id, List.of()) : List.of();
    }

    /** The right branch's own statements against the object with this id, on the same terms. */
    public List<ChangeSet> rightStatements(StableId id) {
        return changes.changedRight(id) ? rightStatements.getOrDefault(id, List.of()) : List.of();
    }
}
