package org.example.adapters;

import net.sf.jsqlparser.statement.create.table.ColumnDefinition;
import org.example.models.schema.ColumnModel;

import java.util.List;

/**
 * Maps one JSqlParser {@link ColumnDefinition} to a {@link ColumnModel} - shared by {@code CREATE TABLE} and
 * {@code ALTER TABLE ADD COLUMN}, since both hand the parser the identical column-definition grammar. Reads the
 * name and type directly off the {@link ColumnDefinition}; everything else about the column - {@code NOT NULL},
 * {@code DEFAULT}, identity/auto-increment - is {@link ColumnSpecs}'s job.
 */
final class ColumnMapper {
    ColumnModel toColumnModel(ColumnDefinition column, String tableName, List<List<String>> identitySpecs) {
        String name = SqlIdentifiers.normalize(column.getColumnName());
        String type = SqlIdentifiers.normalizeType(column.getColDataType().getDataType());
        ColumnSpecs specs = ColumnSpecs.parse(column.getColumnSpecs(), identitySpecs, name, tableName);
        return ColumnModel.unassigned(name, type, !specs.notNull(), specs.defaultValue(), specs.generatedAs());
    }
}
