package org.example.schema;

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
}
