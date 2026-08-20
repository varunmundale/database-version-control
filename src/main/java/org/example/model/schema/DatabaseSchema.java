package org.example.model.schema;

import java.util.List;
import java.util.Objects;

/** Database-independent representation of a single database schema. */
public record DatabaseSchema(String dialect, String schema, List<TableModel> tables) {
    public DatabaseSchema {
        Objects.requireNonNull(dialect, "dialect must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        tables = List.copyOf(tables);
    }
}
