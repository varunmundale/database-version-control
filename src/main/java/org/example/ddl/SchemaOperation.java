package org.example.ddl;

import java.util.List;

/**
 * A single DDL statement's meaning, extracted from vendor-specific syntax by a {@link DdlParser} into a shape
 * {@link SchemaOperationApplier} can apply without knowing anything about that syntax. This is the seam between
 * "understanding what a statement says" (per vendor) and "changing the model accordingly" (shared).
 */
public sealed interface SchemaOperation {
    /** The table this operation creates or targets. */
    String tableName();

    record CreateTable(String tableName, boolean ifNotExists, List<ColumnDefinition> columns) implements SchemaOperation {
    }

    /** A brand new column - {@link SchemaOperationApplier} gives it a fresh stable id, since it has no prior identity to carry forward. */
    record AddColumn(String tableName, ColumnDefinition column) implements SchemaOperation {
    }

    record DropColumn(String tableName, String columnName) implements SchemaOperation {
    }

    /** Renames a column in place - {@link SchemaOperationApplier} carries its stable id forward under the new name. */
    record RenameColumn(String tableName, String oldName, String newName) implements SchemaOperation {
    }

    /** Changes a column's type in place - {@link SchemaOperationApplier} keeps its name and stable id unchanged. */
    record AlterColumnType(String tableName, String columnName, String newType) implements SchemaOperation {
    }
}
