package org.example.core.differ;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which side actually moved each object two branches differ on. Two schemas alone can't say who changed what - a
 * column differing on both sides could be a genuine conflict or just one branch not having caught up - so this
 * judges each side against the schema both branches shared before they diverged (replayed by {@link Differ}).
 * Matching is by {@link StableId} throughout, so a rename on one side racing a modification on the other is still
 * caught as a conflict.
 *
 * @param left  ids the left branch changed since the shared history - added, dropped or altered
 * @param right the same for the right branch
 */
record SideChanges(Set<StableId> left, Set<StableId> right) {

    /** Judges everything {@code tables} reports as differing against the schema {@code base} the branches shared. */
    static SideChanges since(Map<String, TableModel> base, List<TableDiff> tables) {
        Set<StableId> left = new LinkedHashSet<>();
        Set<StableId> right = new LinkedHashSet<>();
        // Keyed by id, not name: after a one-sided rename the base table's name is the old one, so looking it up
        // by the diff's new name would miss it and misreport every column as freshly added.
        Map<StableId, TableModel> baseById = TableDiff.byId(base.values());
        for (TableDiff tableDiff : tables) {
            TableModel baseTable = baseById.get(tableDiff.id());
            collect(left, right, List.of(tableDiff), baseTable == null ? List.of() : List.of(baseTable));
            collect(left, right, tableDiff.columnDiffs(), baseTable == null ? List.of() : baseTable.columns());
            collect(left, right, tableDiff.constraintDiffs(), baseTable == null ? List.of() : baseTable.constraints());
            collect(left, right, tableDiff.indexDiffs(), baseTable == null ? List.of() : baseTable.indexes());
        }
        return new SideChanges(left, right);
    }

    boolean changedLeft(StableId id) {
        return left.contains(id);
    }

    boolean changedRight(StableId id) {
        return right.contains(id);
    }

    /** True when both branches moved this object - only a person can settle that. */
    boolean conflicting(StableId id) {
        return changedLeft(id) && changedRight(id);
    }

    private static <S extends SchemaElement<S>> void collect(Set<StableId> left, Set<StableId> right,
                                                             List<? extends ElementDiff<S>> diffs, List<S> baseMembers) {
        Map<StableId, S> byId = TableDiff.byId(baseMembers);
        for (ElementDiff<S> diff : diffs) {
            S base = byId.get(diff.id());
            if (changed(base, diff.left())) {
                left.add(diff.id());
            }
            if (changed(base, diff.right())) {
                right.add(diff.id());
            }
        }
    }

    /** True when this side added, dropped or altered the object relative to the shared history. */
    private static <S extends SchemaElement<S>> boolean changed(S base, S side) {
        if (base == null) {
            return side != null;
        }
        return side == null || base.differsFrom(side);
    }
}
