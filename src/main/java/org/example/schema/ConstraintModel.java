package org.example.schema;

import java.util.List;
import java.util.Objects;

public record ConstraintModel(StableId id, String name, ConstraintType type, List<StableId> columnIds,
                              StableId referencedTableId, List<StableId> referencedColumnIds) {
    public ConstraintModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(type, "type must not be null");
        columnIds = List.copyOf(columnIds);
        referencedColumnIds = List.copyOf(referencedColumnIds);
        if (type == ConstraintType.FOREIGN_KEY && referencedTableId == null) {
            throw new IllegalArgumentException("foreign keys must reference a table");
        }
    }
}
