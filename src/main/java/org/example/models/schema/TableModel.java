package org.example.models.schema;

import java.util.List;
import java.util.Objects;

public record TableModel(StableId id, String schema, String name, List<ColumnModel> columns,
                         List<IndexModel> indexes, List<ConstraintModel> constraints) {
    public TableModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        Objects.requireNonNull(name, "name must not be null");
        columns = List.copyOf(columns);
        indexes = List.copyOf(indexes);
        constraints = List.copyOf(constraints);
    }

    /** This table with one of its three member lists swapped; identity, schema and everything else are carried over. */
    public TableModel withColumns(List<ColumnModel> columns) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }

    public TableModel withIndexes(List<IndexModel> indexes) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }

    public TableModel withConstraints(List<ConstraintModel> constraints) {
        return new TableModel(id, schema, name, columns, indexes, constraints);
    }
}
