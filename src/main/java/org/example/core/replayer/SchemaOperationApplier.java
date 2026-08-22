package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintModel;
import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Applies an already-parsed {@link SchemaOperation} to the internal model, with no knowledge of any vendor's DDL
 * syntax. Every {@link org.example.parsers.DdlParser}, whatever dialect it understands, produces operations that
 * land here unchanged.
 *
 * <p>Each application opens a {@link TableEditor} on the table the operation names, then edits the one member list
 * the operation is about. Finding the table, looking a member up by name, rejecting a name already taken and
 * folding the result back into the table all belong to the editor and its {@link TableMembers}, so what is left
 * here is only what distinguishes one operation from another.
 *
 * <p>The dispatch is a pattern switch over a sealed type rather than a registry of handlers: the compiler then
 * proves every operation is handled, and adding one to {@link SchemaOperation} fails the build here until it is.
 */
public final class SchemaOperationApplier {

    /**
     * @param existing the table's current state, or {@code null} if it does not exist yet
     */
    public TableModel apply(String schema, SchemaOperation operation, TableModel existing) {
        TableEditor editor = TableEditor.on(schema, operation.tableName(), existing);
        return switch (operation) {
            case SchemaOperation.CreateTable op -> createTable(editor, op);
            case SchemaOperation.AddColumn op -> editor.columns().add(op.column().identifiedIn(editor.tableId()));
            case SchemaOperation.DropColumn op -> editor.columns().remove(op.columnName());
            case SchemaOperation.RenameColumn op -> rewriteColumn(editor, op.oldName(), column -> column.renamedTo(op.newName()));
            case SchemaOperation.AlterColumnType op -> rewriteColumn(editor, op.columnName(), column -> column.retyped(op.newType()));
            case SchemaOperation.AddConstraint op -> editor.constraints().add(constraint(editor, op));
            case SchemaOperation.DropConstraint op -> editor.constraints().remove(op.constraintName());
            case SchemaOperation.CreateIndex op -> editor.indexes().add(index(editor, op));
        };
    }

    /** The one operation that expects no table to be there yet - and the one place a table's stable id is minted. */
    private TableModel createTable(TableEditor editor, SchemaOperation.CreateTable op) {
        if (editor.tableExists()) {
            if (op.ifNotExists()) {
                return editor.table();
            }
            throw new IllegalArgumentException("Table already exists: " + op.tableName());
        }
        StableId tableId = StableId.forTable(editor.schema(), op.tableName());
        List<ColumnModel> columns = op.columns().stream().map(column -> column.identifiedIn(tableId)).toList();
        return new TableModel(tableId, editor.schema(), op.tableName(), columns, List.of(), List.of());
    }

    /**
     * The two operations that change a column in place rather than replacing it. Both go through
     * {@link ColumnModel}'s own rewrites, which carry the existing stable id forward - so a column renamed on one
     * branch and modified under its old name on another are still recognized, by id, as the same column when diffed.
     */
    private TableModel rewriteColumn(TableEditor editor, String columnName, UnaryOperator<ColumnModel> rewrite) {
        TableMembers<ColumnModel> columns = editor.columns();
        ColumnModel target = columns.require(columnName);
        return columns.replace(target, rewrite.apply(target));
    }

    private ConstraintModel constraint(TableEditor editor, SchemaOperation.AddConstraint op) {
        StableId referencedTableId = op.referencedTableName() == null
                ? null
                : StableId.forTable(editor.schema(), op.referencedTableName());
        return new ConstraintModel(StableId.forConstraint(editor.tableId(), op.constraintName()),
                op.constraintName(), op.type(), editor.columns().idsOf(op.columnNames()),
                referencedTableId, referencedColumnIds(referencedTableId, op.referencedColumnNames()));
    }

    private IndexModel index(TableEditor editor, SchemaOperation.CreateIndex op) {
        return new IndexModel(StableId.forIndex(editor.tableId(), op.indexName()),
                op.indexName(), op.unique(), editor.columns().idsOf(op.columnNames()));
    }

    /**
     * A foreign key's target lives on another table, which the replay may not have built yet - and does not need
     * to have, since a stable id is derived from the logical path rather than looked up.
     */
    private static List<StableId> referencedColumnIds(StableId referencedTableId, List<String> columnNames) {
        if (referencedTableId == null) {
            return List.of();
        }
        return columnNames.stream().map(name -> StableId.forColumn(referencedTableId, name)).toList();
    }
}
