package org.example.core.differ;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintModel;
import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * One table, matched by name (a table rename is not tracked, so a renamed table shows up as one disappearing and a
 * different one appearing, not as a rename), and everything that differs about it between two sides: its columns,
 * its constraints and its indexes. Exactly one of {@code left}/{@code right} is {@code null} when the table exists
 * on only one side - see {@link #side()}. Note that {@link Side#CONFLICT} here just means "present on both sides";
 * unlike a {@link ColumnDiff}, that is not itself noteworthy - the three diff lists are what say whether the table
 * actually changed.
 *
 * <p>Matching a table's contents is this class's job, via {@link #between}: {@link DatabaseDiff} pairs up tables and
 * leaves each pairing to work out what changed inside it.
 */
public record TableDiff(String tableName, TableModel left, TableModel right, List<ColumnDiff> columnDiffs,
                        List<ConstraintDiff> constraintDiffs, List<IndexDiff> indexDiffs) {
    public TableDiff {
        Side.of(left, right); // validates at least one side is present
        columnDiffs = List.copyOf(columnDiffs);
        constraintDiffs = List.copyOf(constraintDiffs);
        indexDiffs = List.copyOf(indexDiffs);
    }

    /**
     * Diffs one table pairing, matching columns, constraints and indexes by stable id - which, thanks to
     * {@link org.example.core.replayer.Replayer}'s rename-aware replay, survives a {@code RENAME COLUMN} on either
     * side. A missing side contributes nothing, so everything the side that does exist has is reported. Each list
     * is ordered by name.
     */
    public static TableDiff between(String tableName, TableModel left, TableModel right) {
        List<ColumnDiff> columns = matchById(columnsOf(left), columnsOf(right), ColumnModel::id, ColumnModel::differsFrom)
                .stream().map(match -> new ColumnDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(ColumnDiff::columnName)).toList();
        List<ConstraintDiff> constraints = matchById(constraintsOf(left), constraintsOf(right),
                ConstraintModel::id, ConstraintModel::differsFrom)
                .stream().map(match -> new ConstraintDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(ConstraintDiff::constraintName)).toList();
        List<IndexDiff> indexes = matchById(indexesOf(left), indexesOf(right), IndexModel::id, IndexModel::differsFrom)
                .stream().map(match -> new IndexDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(IndexDiff::indexName)).toList();
        return new TableDiff(tableName, left, right, columns, constraints, indexes);
    }

    /** LEFT/RIGHT when the table exists on only one side; CONFLICT when it exists on both, regardless of whether anything inside it differs. */
    public Side side() {
        return Side.of(left, right);
    }

    public boolean onlyOnLeft() {
        return side() == Side.LEFT;
    }

    public boolean onlyOnRight() {
        return side() == Side.RIGHT;
    }

    /** True when the table exists on both sides and nothing inside it differs - i.e. there is nothing to report. */
    public boolean isEmpty() {
        return side() == Side.CONFLICT && columnDiffs.isEmpty() && constraintDiffs.isEmpty() && indexDiffs.isEmpty();
    }

    /**
     * Pairs up two sides' objects by stable id and keeps only the ids worth reporting: present on one side only, or
     * present on both but differing. The one matching rule columns, constraints and indexes all share.
     */
    private static <M> List<Match<M>> matchById(List<M> left, List<M> right, Function<M, StableId> idOf, BiPredicate<M, M> differs) {
        Map<StableId, M> leftById = byId(left, idOf);
        Map<StableId, M> rightById = byId(right, idOf);

        Set<StableId> ids = new LinkedHashSet<>(leftById.keySet());
        ids.addAll(rightById.keySet());

        List<Match<M>> matches = new ArrayList<>();
        for (StableId id : ids) {
            M leftItem = leftById.get(id);
            M rightItem = rightById.get(id);
            if (leftItem == null || rightItem == null || differs.test(leftItem, rightItem)) {
                matches.add(new Match<>(id, leftItem, rightItem));
            }
        }
        return matches;
    }

    private static <M> Map<StableId, M> byId(List<M> items, Function<M, StableId> idOf) {
        Map<StableId, M> byId = new LinkedHashMap<>();
        items.forEach(item -> byId.put(idOf.apply(item), item));
        return byId;
    }

    private static List<ColumnModel> columnsOf(TableModel table) {
        return table == null ? List.of() : table.columns();
    }

    private static List<ConstraintModel> constraintsOf(TableModel table) {
        return table == null ? List.of() : table.constraints();
    }

    private static List<IndexModel> indexesOf(TableModel table) {
        return table == null ? List.of() : table.indexes();
    }

    /** One object as it stands on each side; at least one is always present. */
    private record Match<M>(StableId id, M left, M right) {
    }
}
