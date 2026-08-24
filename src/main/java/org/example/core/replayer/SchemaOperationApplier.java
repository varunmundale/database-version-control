package org.example.core.replayer;

import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.List;

/**
 * Applies an already-parsed {@link SchemaOperation} to one table; dialect-agnostic, since every {@link
 * org.example.adapters.DdlParser} produces operations that land here unchanged. {@link #apply} can return
 * {@code null} (only for {@link SchemaOperation.DropTable}) meaning "no table left under this name" - moving the
 * result to the right map key is {@link Replayer#apply}'s job, since this class has no view of the schema as a whole.
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

    /** A stable id is derived, not looked up, so the referenced table need not exist yet in the replay. */
    private static List<StableId> referencedColumnIds(StableId referencedTableId, List<String> columnNames) {
        if (referencedTableId == null) {
            return List.of();
        }
        return columnNames.stream().map(name -> StableId.forColumn(referencedTableId, name)).toList();
    }
}
