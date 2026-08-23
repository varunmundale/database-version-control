package org.example.core.differ;

import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which of the objects two branches differ on they have <em>both</em> changed - the difference between a genuine
 * conflict and a change one branch simply hasn't got yet.
 *
 * <p>Two schemas cannot answer that. A column that reads {@code varchar(100)} on one side and {@code varchar(10)}
 * on the other is a disagreement only if both branches moved it; if one branch left it exactly as it was when the
 * two diverged, there is nothing to resolve - the other branch's statement is simply brought in, which is what a
 * merge is for. Comparing the two sides alone cannot tell those apart, so it called both a conflict, and a branch
 * that had just been reset back to before its own change was still refused the merge that would have restored it.
 *
 * <p>So the judgment is three-way: the object as it stands on each side, against the object as it stood in the two
 * branches' shared history (every commit both of them carry, replayed by {@link Differ}). Both sides changed it - added it, dropped it, or
 * altered it - and it is a conflict; one side or neither, and it is not. Matching is by {@link StableId}
 * throughout, so a rename on one side racing a modification on the other is still caught: both sides changed the
 * same object, one of them only its name.
 */
final class SchemaConflicts {

    /** The ids, among everything {@code tables} reports as differing, that both branches changed since {@code base}. */
    Set<StableId> in(List<TableDiff> tables, Map<String, TableModel> base) {
        Set<StableId> conflicts = new LinkedHashSet<>();
        for (TableDiff tableDiff : tables) {
            TableModel baseTable = base.get(tableDiff.tableName());
            collect(conflicts, tableDiff.columnDiffs(), baseTable == null ? List.of() : baseTable.columns());
            collect(conflicts, tableDiff.constraintDiffs(), baseTable == null ? List.of() : baseTable.constraints());
            collect(conflicts, tableDiff.indexDiffs(), baseTable == null ? List.of() : baseTable.indexes());
        }
        return conflicts;
    }

    private static <S extends SchemaElement<S>> void collect(Set<StableId> conflicts,
                                                             List<? extends ElementDiff<S>> diffs, List<S> baseMembers) {
        Map<StableId, S> byId = TableDiff.byId(baseMembers);
        for (ElementDiff<S> diff : diffs) {
            S base = byId.get(diff.id());
            if (changed(base, diff.left()) && changed(base, diff.right())) {
                conflicts.add(diff.id());
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
