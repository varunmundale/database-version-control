package org.example.core.differ;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which side actually moved each of the objects two branches differ on - the one question two schemas cannot
 * answer between them, and the one everything else about a divergence turns on.
 *
 * <p>A column that reads {@code VARCHAR(100)} on one side and {@code VARCHAR(10)} on the other tells you nothing
 * about who changed it. If both branches moved it, there is a genuine conflict and no merge can pick a winner. If
 * only one did, the other is simply behind, and the merge exists to bring that change in. And if a branch moved it
 * and then moved it back - a compensating statement written to settle a conflict - it has changed nothing at all,
 * however many statements it took to get there.
 *
 * <p>So the judgment is three-way: the object as it stands on each side, against the object as it stood in the
 * history both branches carry (replayed by {@link Differ}). Matching is by {@link StableId} throughout, so a
 * rename counts as a change like any other and a rename on one side racing a modification on the other is still
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
        // Keyed by id, not by tableDiff.tableName(): after a one-sided rename the base table's name is the old
        // one, and looking it up by the diff's (possibly new) name would miss it entirely - reporting every one
        // of its columns as freshly added by whichever side actually just renamed it, a conflict neither branch
        // is responsible for.
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

    /** True when the left branch is responsible for the state its side of this object is in. */
    boolean changedLeft(StableId id) {
        return left.contains(id);
    }

    /** True when the right branch is responsible for the state its side of this object is in. */
    boolean changedRight(StableId id) {
        return right.contains(id);
    }

    /** True when both branches moved this object, which is what nothing but a person can settle. */
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

    /** True when this side has done something to the object since the shared history: added it, dropped it or altered it. */
    private static <S extends SchemaElement<S>> boolean changed(S base, S side) {
        if (base == null) {
            return side != null;
        }
        return side == null || base.differsFrom(side);
    }
}
