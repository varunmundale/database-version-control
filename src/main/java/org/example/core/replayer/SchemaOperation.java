package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintType;

import java.util.List;

/**
 * A single DDL statement's meaning, extracted from vendor-specific syntax by a {@link org.example.parsers.DdlParser}
 * into a shape {@link SchemaOperationApplier} can apply without knowing anything about that syntax. This is the
 * seam between "understanding what a statement says" (per vendor) and "changing the model accordingly" (shared).
 */
public sealed interface SchemaOperation {
    /** The table this operation creates or targets. */
    String tableName();

    record CreateTable(String tableName, boolean ifNotExists, List<ColumnModel> columns) implements SchemaOperation {
    }

    /** A brand new column - {@link SchemaOperationApplier} gives it a fresh stable id, since it has no prior identity to carry forward. */
    record AddColumn(String tableName, ColumnModel column) implements SchemaOperation {
    }

    record DropColumn(String tableName, String columnName) implements SchemaOperation {
    }

    /** Renames a column in place - {@link SchemaOperationApplier} carries its stable id forward under the new name. */
    record RenameColumn(String tableName, String oldName, String newName) implements SchemaOperation {
    }

    /** Changes a column's type in place - {@link SchemaOperationApplier} keeps its name and stable id unchanged. */
    record AlterColumnType(String tableName, String columnName, String newType) implements SchemaOperation {
    }

    /**
     * Adds a named constraint over one or more of the table's columns. Carries the column <em>names</em> the
     * statement mentioned, not ids: resolving those to the columns they refer to is
     * {@link SchemaOperationApplier}'s job, the same way it assigns ids to new columns.
     * {@code referencedTableName}/{@code referencedColumnNames} are populated for a foreign key only.
     */
    record AddConstraint(String tableName, String constraintName, ConstraintType type, List<String> columnNames,
                         String referencedTableName, List<String> referencedColumnNames) implements SchemaOperation {
        public AddConstraint {
            columnNames = List.copyOf(columnNames);
            referencedColumnNames = List.copyOf(referencedColumnNames);
        }
    }

    record DropConstraint(String tableName, String constraintName) implements SchemaOperation {
    }

    /** Creates a named index over one or more of the table's columns. Column names are resolved by the applier. */
    record CreateIndex(String tableName, String indexName, boolean unique, List<String> columnNames) implements SchemaOperation {
        public CreateIndex {
            columnNames = List.copyOf(columnNames);
        }
    }
}
