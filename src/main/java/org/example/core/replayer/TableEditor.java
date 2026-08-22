package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintModel;
import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

/**
 * The table a single {@link SchemaOperation} targets, opened for editing. Its whole job is to answer "which table,
 * and does it exist" once - so that "unknown table" is raised in one place rather than per operation - and to hand
 * out a {@link TableMembers} view of whichever member list the operation is about.
 *
 * <p>The table may legitimately be absent: {@link SchemaOperation.CreateTable} is the one operation that expects
 * that, and asks with {@link #tableExists()}. Every other operation reaches it through {@link #table()}.
 *
 * <p>Nothing here mutates: every edit returns a new {@link TableModel} and leaves this editor's view untouched.
 */
final class TableEditor {
    private final String schema;
    private final String tableName;
    private final TableModel table;

    private TableEditor(String schema, String tableName, TableModel table) {
        this.schema = schema;
        this.tableName = tableName;
        this.table = table;
    }

    /** @param table the table's current state, or {@code null} if it does not exist yet */
    static TableEditor on(String schema, String tableName, TableModel table) {
        return new TableEditor(schema, tableName, table);
    }

    String schema() {
        return schema;
    }

    boolean tableExists() {
        return table != null;
    }

    TableModel table() {
        if (table == null) {
            throw new IllegalArgumentException("Unknown table: " + tableName);
        }
        return table;
    }

    StableId tableId() {
        return table().id();
    }

    TableMembers<ColumnModel> columns() {
        return new TableMembers<>("column", tableName, table().columns(), table()::withColumns);
    }

    TableMembers<ConstraintModel> constraints() {
        return new TableMembers<>("constraint", tableName, table().constraints(), table()::withConstraints);
    }

    TableMembers<IndexModel> indexes() {
        return new TableMembers<>("index", tableName, table().indexes(), table()::withIndexes);
    }
}
