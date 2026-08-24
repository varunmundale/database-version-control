package org.example.core.replayer;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintType;

import java.util.List;

/**
 * A single DDL statement's meaning, extracted from vendor-specific syntax by a {@link org.example.adapters.DdlParser}
 * into a shape {@link SchemaOperationApplier} can apply without knowing that syntax. Plain data holding names (not
 * stable ids); {@code sealed} so the applier's switch must cover every variant.
 */
public sealed interface SchemaOperation {
    /** For {@link DropTable}/{@link RenameTable}, the table's name <em>before</em> the statement runs. */
    String tableName();

    record CreateTable(String tableName, List<ColumnModel> columns) implements SchemaOperation {
    }

    record AddColumn(String tableName, ColumnModel column) implements SchemaOperation {
    }

    record DropColumn(String tableName, String columnName) implements SchemaOperation {
    }

    record RenameColumn(String tableName, String oldName, String newName) implements SchemaOperation {
    }

    /** Leaves no table behind under {@code tableName}; see {@link Replayer#apply}. */
    record DropTable(String tableName) implements SchemaOperation {
    }

    /**
     * The table {@code tableName} carries its {@link org.example.models.schema.StableId} over under
     * {@code newName} - see {@link org.example.models.schema.TableModel#renamedTo}.
     */
    record RenameTable(String tableName, String newName) implements SchemaOperation {
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
