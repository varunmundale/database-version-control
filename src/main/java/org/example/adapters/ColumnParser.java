package org.example.adapters;

import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import org.example.models.schema.ColumnModel;

import java.util.List;
import java.util.Locale;

/**
 * Builds a {@link ColumnModel} from one JSqlParser {@link ColumnDefinition} - shared by {@code CREATE TABLE} and
 * {@code ALTER TABLE ADD COLUMN}, since both hand the parser the identical column-definition grammar.
 *
 * <p>The two column specs the model actually carries are {@code NOT NULL} and {@code DEFAULT}. Anything else a
 * column declares - {@code PRIMARY KEY}, {@code UNIQUE}, {@code REFERENCES}, {@code CHECK} - is a constraint
 * wearing a column's clothing, and is rejected here, with a message naming the {@code ALTER TABLE ADD CONSTRAINT}
 * form to write instead, rather than being quietly dropped.
 */
final class ColumnParser {
    ColumnModel toColumnModel(ColumnDefinition column, String tableName) {
        String name = SqlIdentifiers.normalize(column.getColumnName());
        String type = SqlIdentifiers.normalizeType(column.getColDataType().getDataType());
        Specs specs = Specs.of(column.getColumnSpecs(), name, tableName);
        return ColumnModel.unassigned(name, type, !specs.notNull(), specs.defaultValue());
    }

    private record Specs(boolean notNull, String defaultValue) {
        private static Specs of(List<String> specs, String columnName, String tableName) {
            if (specs == null || specs.isEmpty()) {
                return new Specs(false, null);
            }
            boolean notNull = false;
            String defaultValue = null;
            for (int index = 0; index < specs.size(); index++) {
                String token = specs.get(index);
                if (token.equalsIgnoreCase("NOT") && index + 1 < specs.size() && specs.get(index + 1).equalsIgnoreCase("NULL")) {
                    notNull = true;
                    index++;
                } else if (token.equalsIgnoreCase("NULL")) {
                    continue;
                } else if (token.equalsIgnoreCase("DEFAULT") && index + 1 < specs.size()) {
                    defaultValue = specs.get(index + 1);
                    index++;
                } else {
                    throw inlineConstraint(specs, index, columnName, tableName);
                }
            }
            return new Specs(notNull, defaultValue);
        }

        /** Names the constraint the column tried to declare, and suggests the statement that should carry it instead. */
        private static IllegalArgumentException inlineConstraint(List<String> specs, int index, String columnName, String tableName) {
            String token = specs.get(index).toUpperCase(Locale.ROOT);
            boolean primaryKey = token.equals("PRIMARY");
            String kind = primaryKey ? "PRIMARY KEY" : token;
            String suggestion = switch (kind) {
                case "PRIMARY KEY" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_pkey PRIMARY KEY ("
                        + columnName + ")";
                case "UNIQUE" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                        + "_key UNIQUE (" + columnName + ")";
                case "REFERENCES" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                        + "_fkey FOREIGN KEY (" + columnName + ") REFERENCES <table> (<column>)";
                default -> "ALTER TABLE " + tableName + " ADD CONSTRAINT <name> " + kind + " (...)";
            };
            return new IllegalArgumentException("Column '" + columnName + "' declares " + kind
                    + ", which is a constraint rather than a property of the column itself."
                    + " Define the column by itself and add the constraint separately, e.g. " + suggestion + "."
                    + " Only NOT NULL and DEFAULT may be written on a column.");
        }
    }
}
