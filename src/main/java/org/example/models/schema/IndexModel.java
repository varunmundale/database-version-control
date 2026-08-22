package org.example.models.schema;

import java.util.List;
import java.util.Objects;

/**
 * A named index over one or more of a table's columns, held by stable id rather than by column name so it survives
 * a later {@code RENAME COLUMN} of a column it covers. Like a constraint, its own identity comes from its name.
 */
public record IndexModel(StableId id, String name, boolean unique, List<StableId> columnIds)
        implements SchemaElement<IndexModel> {
    public IndexModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        columnIds = List.copyOf(columnIds);
    }

    /** True when this index, matched by id with another, covers different columns or differs in uniqueness. */
    public boolean differsFrom(IndexModel other) {
        return unique != other.unique || !columnIds.equals(other.columnIds);
    }

    /** How this index reads in {@code dbgit diff} output, e.g. {@code "UNIQUE INDEX"}. */
    public String definition() {
        return unique ? "UNIQUE INDEX" : "INDEX";
    }
}
