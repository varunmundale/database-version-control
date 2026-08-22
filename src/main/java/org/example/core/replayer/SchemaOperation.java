package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintType;

import java.util.List;

/**
 * A single DDL statement's meaning, extracted from vendor-specific syntax by a {@link org.example.parsers.DdlParser}
 * into a shape {@link SchemaOperationApplier} can apply without knowing anything about that syntax. This is the
 * seam between "understanding what a statement says" (per vendor) and "changing the model accordingly" (shared).
 *
 * <p>These are plain data, deliberately: they carry what the statement said and nothing about what it does to a
 * table. Names, not stable ids, are what a statement mentions, so names are what these hold - resolving them is
 * the applier's job. Keeping the whole vocabulary in one file is also the point, since it is exactly the set of
 * DDL {@code dbgit add} accepts, and {@code sealed} is what makes the compiler enforce that the applier covers it.
 */
public sealed interface SchemaOperation {
    /** The table this operation creates or targets. */
    String tableName();

    record CreateTable(String tableName, boolean ifNotExists, List<ColumnModel> columns) implements SchemaOperation {
    }

    record AddColumn(String tableName, ColumnModel column) implements SchemaOperation {
    }

    record DropColumn(String tableName, String columnName) implements SchemaOperation {
    }

    record RenameColumn(String tableName, String oldName, String newName) implements SchemaOperation {
    }

    record AlterColumnType(String tableName, String columnName, String newType) implements SchemaOperation {
    }

    /** {@code referencedTableName}/{@code referencedColumnNames} are populated for a foreign key only. */
    record AddConstraint(String tableName, String constraintName, ConstraintType type, List<String> columnNames,
                         String referencedTableName, List<String> referencedColumnNames) implements SchemaOperation {
        public AddConstraint {
            columnNames = List.copyOf(columnNames);
            referencedColumnNames = List.copyOf(referencedColumnNames);
        }
    }

    record DropConstraint(String tableName, String constraintName) implements SchemaOperation {
    }

    record CreateIndex(String tableName, String indexName, boolean unique, List<String> columnNames) implements SchemaOperation {
        public CreateIndex {
            columnNames = List.copyOf(columnNames);
        }
    }
}
