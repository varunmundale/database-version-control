package org.example.models.schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * A table's schema, and the only place that edits it - {@link org.example.core.replayer.SchemaOperationApplier}
 * just dispatches to these methods. Columns, constraints and indexes are all edited by the same shape (look a
 * member up by name, reject a name already taken, fold the result back in), written once as the generic helpers
 * below rather than per member kind.
 */
public record TableModel(StableId id, String schema, String name, List<ColumnModel> columns,
                         List<IndexModel> indexes, List<ConstraintModel> constraints) implements SchemaElement<TableModel> {
    public TableModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(name, "name must not be null");
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        constraints = List.copyOf(constraints);
    }

    /** A brand-new table: mints its own stable id and gives each column theirs. */
    public static TableModel create(String schema, String name, List<ColumnModel> columns) {
        StableId tableId = StableId.forTable(schema, name);
        List<ColumnModel> identified = columns.stream().map(column -> column.identifiedIn(tableId)).toList();
        return new TableModel(tableId, schema, name, identified, List.of(), List.of());
    }

    public TableModel addColumn(ColumnModel column) {
        requireAbsent(columns, "column", column.name());
        return withColumns(append(columns, column.identifiedIn(id)));
    }

    public TableModel dropColumn(String columnName) {
        return withColumns(remove(columns, require(columns, "column", columnName)));
    }

    /**
     * The same column under a new name; its stable id is carried over, which is what makes a rename read as one
     * column that changed rather than one dropped and another added.
     */
    public TableModel renameColumn(String oldName, String newName) {
        return replaceColumn(oldName, column -> column.renamedTo(newName));
    }

    public TableModel retypeColumn(String columnName, String newType) {
        return replaceColumn(columnName, column -> column.retyped(newType));
    }

    /** Same table, new name: the stable id (and every column/constraint/index id derived from it) is carried over
     *  unchanged, so members don't read as replaced. */
    public TableModel renamedTo(String newName) {
        return new TableModel(id, schema, newName, columns, indexes, constraints);
    }

    /**
     * True only when the table was renamed - deliberately narrower than other {@link SchemaElement}s, since two
     * branches editing different columns of the same table must not read as both having "changed the table"
     * (that would make {@link org.example.core.differ.SideChanges} call it a conflict). Column/constraint/index
     * changes are reported separately by {@link org.example.core.differ.TableDiff}.
     */
    @Override
    public boolean differsFrom(TableModel other) {
        return !name.equals(other.name);
    }

    private TableModel replaceColumn(String columnName, UnaryOperator<ColumnModel> rewrite) {
        ColumnModel target = require(columns, "column", columnName);
        ColumnModel replacement = rewrite.apply(target);
        if (!replacement.name().equals(target.name())) {
            requireAbsent(columns, "column", replacement.name());
        }
        return withColumns(columns.stream().map(column -> column == target ? replacement : column).toList());
    }

    /**
     * {@code referencedTableId}/{@code referencedColumnIds} are resolved by the caller: they name a different
     * table, possibly one this replay has not built yet, so this table has nothing to look them up against.
     */
    public TableModel addConstraint(String constraintName, ConstraintType type, List<String> columnNames,
                                     StableId referencedTableId, List<StableId> referencedColumnIds) {
        requireAbsent(constraints, "constraint", constraintName);
        ConstraintModel constraint = new ConstraintModel(StableId.forConstraint(id, constraintName), constraintName,
                type, columnIds(columnNames), referencedTableId, referencedColumnIds);
        return withConstraints(append(constraints, constraint));
    }

    public TableModel dropConstraint(String constraintName) {
        return withConstraints(remove(constraints, require(constraints, "constraint", constraintName)));
    }

    public TableModel createIndex(String indexName, boolean unique, List<String> columnNames) {
        requireAbsent(indexes, "index", indexName);
        IndexModel index = new IndexModel(StableId.forIndex(id, indexName), indexName, unique, columnIds(columnNames));
        return withIndexes(append(indexes, index));
    }

    /**
     * Resolves the names a constraint or index covers to the stable ids of the columns they name - which is what
     * gets stored, so that covering a column survives a later {@code RENAME COLUMN} of it.
     */
    private List<StableId> columnIds(List<String> columnNames) {
        return columnNames.stream().map(columnName -> require(columns, "column", columnName).id()).toList();
    }

    /** This table with one of its three member lists swapped; identity, schema and everything else are carried over. */
    public TableModel withColumns(List<ColumnModel> columns) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }

    public TableModel withIndexes(List<IndexModel> indexes) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }

    public TableModel withConstraints(List<ConstraintModel> constraints) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }

    /** The member called {@code memberName}, or a failure naming this table. */
    private <S extends SchemaElement<S>> S require(List<S> members, String kind, String memberName) {
        return members.stream().filter(member -> member.name().equals(memberName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown " + kind + " '" + memberName + "' on table '" + name + "'"));
    }

    private <S extends SchemaElement<S>> void requireAbsent(List<S> members, String kind, String memberName) {
        if (members.stream().anyMatch(member -> member.name().equals(memberName))) {
            throw new IllegalArgumentException(capitalize(kind) + " already exists: " + memberName);
        }
    }

    private static <S> List<S> append(List<S> members, S member) {
        List<S> updated = new ArrayList<>(members);
        updated.add(member);
        return updated;
    }

    private static <S> List<S> remove(List<S> members, S target) {
        return members.stream().filter(member -> member != target).toList();
    }

    private static String capitalize(String kind) {
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }
}
