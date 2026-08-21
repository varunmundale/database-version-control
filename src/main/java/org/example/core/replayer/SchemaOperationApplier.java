package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies an already-parsed {@link SchemaOperation} to the internal model - one pure function per operation kind,
 * with no knowledge of any vendor's DDL syntax. Every {@link org.example.parsers.DdlParser}, whatever dialect it
 * understands, produces operations that land here unchanged.
 *
 * <p>{@code renameColumn} and {@code alterColumnType} are the two operations that mutate a column without
 * replacing it: they carry the existing {@link ColumnModel#id()} forward rather than deriving a new one, so a
 * column renamed on one branch and modified under its old name on another are still recognized, by stable id, as
 * the same object when diffed.
 */
public final class SchemaOperationApplier {

    /**
     * @param existing the table's current state, or {@code null} if it does not exist yet
     */
    public TableModel apply(String schema, SchemaOperation operation, TableModel existing) {
        return switch (operation) {
            case SchemaOperation.CreateTable op -> createTable(schema, op, existing);
            case SchemaOperation.AddColumn op -> addColumn(requireExisting(existing, op.tableName()), op);
            case SchemaOperation.DropColumn op -> dropColumn(requireExisting(existing, op.tableName()), op);
            case SchemaOperation.RenameColumn op -> renameColumn(requireExisting(existing, op.tableName()), op);
            case SchemaOperation.AlterColumnType op -> alterColumnType(requireExisting(existing, op.tableName()), op);
        };
    }

    private TableModel createTable(String schema, SchemaOperation.CreateTable op, TableModel existing) {
        if (existing != null) {
            if (op.ifNotExists()) {
                return existing;
            }
            throw new IllegalArgumentException("Table already exists: " + op.tableName());
        }
        StableId tableId = StableId.of("table", schema + "." + op.tableName());
        List<ColumnModel> columns = new ArrayList<>();
        for (ColumnModel column : op.columns()) {
            columns.add(newColumn(tableId, column));
        }
        return new TableModel(tableId, schema, op.tableName(), columns, List.of(), List.of());
    }

    private TableModel addColumn(TableModel existing, SchemaOperation.AddColumn op) {
        String columnName = op.column().name();
        if (existing.columns().stream().anyMatch(column -> column.name().equals(columnName))) {
            throw new IllegalArgumentException("Column already exists: " + columnName);
        }
        List<ColumnModel> columns = new ArrayList<>(existing.columns());
        columns.add(newColumn(existing.id(), op.column()));
        return withColumns(existing, columns);
    }

    private TableModel dropColumn(TableModel existing, SchemaOperation.DropColumn op) {
        ColumnModel target = columnOrThrow(existing, op.columnName());
        return withColumns(existing, existing.columns().stream().filter(column -> column != target).toList());
    }

    private TableModel renameColumn(TableModel existing, SchemaOperation.RenameColumn op) {
        ColumnModel target = columnOrThrow(existing, op.oldName());
        if (existing.columns().stream().anyMatch(column -> column.name().equals(op.newName()))) {
            throw new IllegalArgumentException("Column already exists: " + op.newName());
        }
        ColumnModel renamed = new ColumnModel(target.id(), op.newName(), target.nativeType(), target.nullable(), target.defaultValue());
        return withColumns(existing, replace(existing, target, renamed));
    }

    private TableModel alterColumnType(TableModel existing, SchemaOperation.AlterColumnType op) {
        ColumnModel target = columnOrThrow(existing, op.columnName());
        ColumnModel updated = new ColumnModel(target.id(), target.name(), op.newType(), target.nullable(), target.defaultValue());
        return withColumns(existing, replace(existing, target, updated));
    }

    private static ColumnModel newColumn(StableId tableId, ColumnModel definition) {
        StableId columnId = StableId.of("column", tableId.value() + "." + definition.name());
        return definition.withId(columnId);
    }

    private static ColumnModel columnOrThrow(TableModel table, String columnName) {
        return table.columns().stream().filter(column -> column.name().equals(columnName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown column '" + columnName + "' on table '" + table.name() + "'"));
    }

    private static List<ColumnModel> replace(TableModel existing, ColumnModel target, ColumnModel replacement) {
        return existing.columns().stream().map(column -> column == target ? replacement : column).toList();
    }

    private static TableModel withColumns(TableModel existing, List<ColumnModel> columns) {
        return new TableModel(existing.id(), existing.schema(), existing.name(), columns, existing.indexes(), existing.constraints());
    }

    private static TableModel requireExisting(TableModel existing, String tableName) {
        if (existing == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return existing;
    }
}
