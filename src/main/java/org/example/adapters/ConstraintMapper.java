package org.example.adapters;

import net.sf.jsqlparser.statement.create.table.CreateTable;
import net.sf.jsqlparser.statement.create.table.ForeignKeyIndex;
import net.sf.jsqlparser.statement.create.table.Index;
import org.example.core.replayer.SchemaOperation;
import org.example.models.schema.ConstraintType;

import java.util.List;

/**
 * Maps a constraint wherever JSqlParser found one declared - as its own {@code ALTER TABLE ADD CONSTRAINT}
 * statement, or (rejected) inline in a {@code CREATE TABLE}'s column or table-level clauses - to a
 * {@link SchemaOperation.AddConstraint}. A constraint dbgit cannot see is a constraint it cannot diff, merge or
 * replay onto a forked branch, which is why the inline forms are rejected rather than silently ignored.
 */
final class ConstraintMapper {
    private static final String PRIMARY_KEY = "PRIMARY KEY";
    private static final String UNIQUE = "UNIQUE";
    private static final String FOREIGN_KEY = "FOREIGN KEY";

    /** A {@code PRIMARY KEY (...)}, {@code UNIQUE (...)}, {@code FOREIGN KEY (...)} or {@code CHECK (...)} clause in the table body. */
    void rejectTableLevelConstraints(CreateTable createTable, String tableName) {
        List<Index> tableLevel = createTable.getIndexes();
        if (tableLevel == null || tableLevel.isEmpty()) {
            return;
        }
        String type = tableLevel.getFirst().getType();
        throw new IllegalArgumentException("CREATE TABLE defines columns only, but this one declares a "
                + (type == null ? "constraint" : type + " constraint") + " on '" + tableName + "'."
                + " Add it separately, e.g. ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName
                + "_pkey PRIMARY KEY (<column>), or CREATE INDEX <name> ON " + tableName + " (<column>).");
    }

    /**
     * The constraint's type is settled first: an unsupported one (a {@code CHECK}, say) must be rejected before
     * anything tries to read the columns it covers, which JSqlParser does not populate for those.
     */
    SchemaOperation.AddConstraint toAddConstraint(String tableName, Index constraint, String ddl) {
        ConstraintType type = constraint instanceof ForeignKeyIndex
                ? ConstraintType.FOREIGN_KEY
                : constraintType(constraint.getType(), ddl);
        if (constraint.getName() == null) {
            throw new IllegalArgumentException("A constraint must be given a name so dbgit can track it across"
                    + " branches: " + ddl + ". Write it as ALTER TABLE " + tableName + " ADD CONSTRAINT <name> ...");
        }
        String name = SqlIdentifiers.normalize(constraint.getName());
        List<String> columns = SqlIdentifiers.normalizeAll(constraint.getColumnsNames());

        if (constraint instanceof ForeignKeyIndex foreignKey) {
            return new SchemaOperation.AddConstraint(tableName, name, type, columns,
                    SqlIdentifiers.normalize(foreignKey.getTable().getName()),
                    SqlIdentifiers.normalizeAll(foreignKey.getReferencedColumnNames()));
        }
        return new SchemaOperation.AddConstraint(tableName, name, type, columns, null, List.of());
    }

    private static ConstraintType constraintType(String type, String ddl) {
        if (PRIMARY_KEY.equalsIgnoreCase(type)) {
            return ConstraintType.PRIMARY_KEY;
        }
        if (UNIQUE.equalsIgnoreCase(type)) {
            return ConstraintType.UNIQUE;
        }
        if (FOREIGN_KEY.equalsIgnoreCase(type)) {
            return ConstraintType.FOREIGN_KEY;
        }
        throw new IllegalArgumentException("Unsupported constraint type "
                + (type == null ? "(CHECK or similar)" : "'" + type + "'") + ": " + ddl
                + ". Only PRIMARY KEY, UNIQUE and FOREIGN KEY constraints are modeled.");
    }
}
