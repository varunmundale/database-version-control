package org.example.core.replayer;

import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.List;

/**
 * Applies an already-parsed {@link SchemaOperation} to one table, with no knowledge of any vendor's DDL syntax.
 * Every {@link org.example.adapters.DdlParser}, whatever dialect it understands, produces operations that land
 * here unchanged.
 *
 * <p>Editing a table - finding a member by name, rejecting a name already taken, folding the result back into the
 * table - is {@link TableModel}'s own job now, so what is left here is only what a {@link TableModel} cannot do for
 * itself: deciding whether a table already existing is an error, and resolving a foreign key's target, which names
 * a different table than the one being edited.
 *
 * <p>The dispatch is a pattern switch over a sealed type rather than a registry of handlers: the compiler then
 * proves every operation is handled, and adding one to {@link SchemaOperation} fails the build here until it is.
 *
 * <p>{@link #apply} can return {@code null} - only {@link SchemaOperation.DropTable} does this, meaning "no table
 * left under this name" - because this class edits one table it is given and has no view of the schema as a whole.
 * Moving the returned table to a new map key (a {@link SchemaOperation.RenameTable}) or removing it (a drop) is the
 * caller's job; see {@link Replayer#apply}, the only caller.
 */
public final class SchemaOperationApplier {

    /**
     * @param existing the table's current state, or {@code null} if it does not exist yet
     * @return the table's new state, or {@code null} if the operation leaves no table behind (a drop)
     */
    public TableModel apply(String schema, SchemaOperation operation, TableModel existing) {
        return switch (operation) {
            case SchemaOperation.CreateTable op -> createTable(schema, existing, op);
            case SchemaOperation.AddColumn op -> table(existing, op.tableName()).addColumn(op.column());
            case SchemaOperation.DropColumn op -> table(existing, op.tableName()).dropColumn(op.columnName());
            case SchemaOperation.RenameColumn op -> table(existing, op.tableName()).renameColumn(op.oldName(), op.newName());
            case SchemaOperation.AlterColumnType op -> table(existing, op.tableName()).retypeColumn(op.columnName(), op.newType());
            case SchemaOperation.AddConstraint op -> addConstraint(table(existing, op.tableName()), op);
            case SchemaOperation.DropConstraint op -> table(existing, op.tableName()).dropConstraint(op.constraintName());
            case SchemaOperation.CreateIndex op -> table(existing, op.tableName()).createIndex(op.indexName(), op.unique(), op.columnNames());
            case SchemaOperation.DropTable op -> dropTable(existing, op);
            case SchemaOperation.RenameTable op -> table(existing, op.tableName()).renamedTo(op.newName());
        };
    }

    /** The one operation that expects no table to be there yet. */
    private TableModel createTable(String schema, TableModel existing, SchemaOperation.CreateTable op) {
        if (existing != null) {
            throw new IllegalArgumentException("Table already exists: " + op.tableName());
        }
        return TableModel.create(schema, op.tableName(), op.columns());
    }

    /** Validates the table exists, then reports there is nothing left of it - the map key move is the caller's job. */
    private TableModel dropTable(TableModel existing, SchemaOperation.DropTable op) {
        table(existing, op.tableName());
        return null;
    }

    private static TableModel table(TableModel existing, String tableName) {
        if (existing == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return existing;
    }

    private TableModel addConstraint(TableModel table, SchemaOperation.AddConstraint op) {
        StableId referencedTableId = op.referencedTableName() == null
                ? null
                : StableId.forTable(table.schema(), op.referencedTableName());
        return table.addConstraint(op.constraintName(), op.type(), op.columnNames(),
                referencedTableId, referencedColumnIds(referencedTableId, op.referencedColumnNames()));
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
