package org.example.models.schema;

import java.util.Objects;

/**
 * A column, either fully identified as part of a {@link TableModel} or, via {@link #unassigned}, just as a
 * vendor's DDL grammar described it - before {@link org.example.core.SchemaOperationApplier} has derived its real
 * stable id from the table it belongs to and given it one with {@link #withId}.
 */
public record ColumnModel(StableId id, String name, String nativeType, boolean nullable, String defaultValue) {
    private static final StableId UNASSIGNED_ID = new StableId("column_unassigned");

    public ColumnModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nativeType, "nativeType must not be null");
    }

    /** A column as parsed from DDL, with no stable id of its own yet - see {@link #withId}. */
    public static ColumnModel unassigned(String name, String nativeType, boolean nullable, String defaultValue) {
        return new ColumnModel(UNASSIGNED_ID, name, nativeType, nullable, defaultValue);
    }

    public ColumnModel withId(StableId id) {
        return new ColumnModel(id, name, nativeType, nullable, defaultValue);
    }

    /** True when this column's type, nullability and default match another's, independent of name or id. */
    public boolean sameDefinitionAs(ColumnModel other) {
        return nativeType.equals(other.nativeType) && nullable == other.nullable
                && Objects.equals(defaultValue, other.defaultValue);
    }

    /** Renders this column's type, nullability and default for {@code dbgit diff}'s conflict messages, e.g. {@code "NUMERIC(10,2) NOT NULL DEFAULT 0"}. */
    public String definition() {
        return nativeType + (nullable ? "" : " NOT NULL") + (defaultValue == null ? "" : " DEFAULT " + defaultValue);
    }

    /** True when this column, matched by id with another, differs in name or definition - i.e. there is something to report. */
    public boolean differsFrom(ColumnModel other) {
        return !name.equals(other.name) || !sameDefinitionAs(other);
    }
}
