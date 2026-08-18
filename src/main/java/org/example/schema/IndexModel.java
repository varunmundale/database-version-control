package org.example.schema;

import java.util.List;
import java.util.Objects;

public record IndexModel(StableId id, String name, boolean unique, List<StableId> columnIds) {
    public IndexModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        columnIds = List.copyOf(columnIds);
    }
}
