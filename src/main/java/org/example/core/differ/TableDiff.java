package org.example.core.differ;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintModel;
import org.example.models.schema.IndexModel;
import org.example.models.schema.SchemaElement;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One table, matched by stable id - which, thanks to {@link TableModel#renamedTo} carrying it over, survives an
 * {@code ALTER TABLE ... RENAME TO} on either side, so a renamed table reads as one table whose name differs
 * rather than one disappearing and a different one appearing - and everything that differs about it between two
 * sides: its columns, its constraints and its indexes. Exactly one of {@code left}/{@code right} is {@code null}
 * when the table exists on only one side - see {@link #side()}.
 *
 * <p>Matching a table's contents is this class's job, via {@link #between}: {@link DatabaseDiff} pairs up tables and
 * leaves each pairing to work out what changed inside it.
 */
public record TableDiff(TableModel left, TableModel right, List<ColumnDiff> columnDiffs,
                        List<ConstraintDiff> constraintDiffs, List<IndexDiff> indexDiffs)
        implements ElementDiff<TableModel> {
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
    public static TableDiff between(TableModel left, TableModel right) {
        List<ColumnDiff> columns = matchById(columnsOf(left), columnsOf(right))
                .stream().map(match -> new ColumnDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(ColumnDiff::columnName)).toList();
        List<ConstraintDiff> constraints = matchById(constraintsOf(left), constraintsOf(right))
                .stream().map(match -> new ConstraintDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(ConstraintDiff::constraintName)).toList();
        List<IndexDiff> indexes = matchById(indexesOf(left), indexesOf(right))
                .stream().map(match -> new IndexDiff(match.id(), match.left(), match.right()))
                .sorted(Comparator.comparing(IndexDiff::indexName)).toList();
        return new TableDiff(left, right, columns, constraints, indexes);
    }

    /** The stable id shared by whichever side(s) have this table. */
    @Override
    public StableId id() {
        return left != null ? left.id() : right.id();
    }

    /** The name to sort and label this diff by - whichever side has the table, left preferred. */
    public String tableName() {
        return left != null ? left.name() : right.name();
    }

    public boolean onlyOnLeft() {
        return side() == Side.LEFT;
    }

    public boolean onlyOnRight() {
        return side() == Side.RIGHT;
    }

    /**
     * True when the table itself - not its contents - was created, dropped or renamed since the other side. Two
     * branches adding different columns to the same table both changed its contents, but neither changed the
     * table by this narrower measure - see {@link TableModel#differsFrom}.
     */
    public boolean tableChanged() {
        return side() != Side.BOTH || left.differsFrom(right);
    }

    /** True when the table itself is unchanged and nothing inside it differs - i.e. there is nothing to report. */
    public boolean isEmpty() {
        return !tableChanged() && columnDiffs.isEmpty() && constraintDiffs.isEmpty() && indexDiffs.isEmpty();
    }

    /**
     * Pairs up two sides' members by stable id and keeps only the ids worth reporting: present on one side only, or
     * present on both but differing. The one matching rule columns, constraints and indexes all share - written
     * once over {@link SchemaElement}, which is what gives all three an id and a way to compare against their kind.
     */
    private static <S extends SchemaElement<S>> List<Match<S>> matchById(Collection<S> left, Collection<S> right) {
        Map<StableId, S> leftById = byId(left);
        Map<StableId, S> rightById = byId(right);

        Set<StableId> ids = new LinkedHashSet<>(leftById.keySet());
        ids.addAll(rightById.keySet());

        List<Match<S>> matches = new ArrayList<>();
        for (StableId id : ids) {
            S leftItem = leftById.get(id);
            S rightItem = rightById.get(id);
            if (leftItem == null || rightItem == null || leftItem.differsFrom(rightItem)) {
                matches.add(new Match<>(id, leftItem, rightItem));
            }
        }
        return matches;
    }

    /** Package-private so {@link SideChanges} and {@link DatabaseDiff} can index members/tables the same way. */
    static <S extends SchemaElement<S>> Map<StableId, S> byId(Collection<S> members) {
        Map<StableId, S> byId = new LinkedHashMap<>();
        members.forEach(member -> byId.put(member.id(), member));
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

    /** One member as it stands on each side; at least one is always present. */
    private record Match<S>(StableId id, S left, S right) {
    }
}
