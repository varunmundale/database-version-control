package org.example.models.schema;

import java.util.List;
import java.util.Objects;

/**
 * A named constraint over one or more of a table's columns, held by stable id rather than by column name so it
 * survives a later {@code RENAME COLUMN} of a column it covers. The constraint's own identity comes from its name,
 * so renaming a constraint reads as one disappearing and another appearing.
 */
public record ConstraintModel(StableId id, String name, ConstraintType type, List<StableId> columnIds,
                              StableId referencedTableId, List<StableId> referencedColumnIds)
        implements SchemaElement<ConstraintModel> {
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

    /** True when this constraint, matched by id with another, covers different columns or means something different. */
    public boolean differsFrom(ConstraintModel other) {
        return type != other.type
                || !columnIds.equals(other.columnIds)
                || !Objects.equals(referencedTableId, other.referencedTableId)
                || !referencedColumnIds.equals(other.referencedColumnIds);
    }

    /** How this constraint reads in {@code dbgit diff} output, e.g. {@code "PRIMARY KEY"}. */
    public String definition() {
        return switch (type) {
            case PRIMARY_KEY -> "PRIMARY KEY";
            case UNIQUE -> "UNIQUE";
            case FOREIGN_KEY -> "FOREIGN KEY";
        };
    }
}
