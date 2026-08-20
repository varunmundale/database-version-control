package org.example.model.schema;

import java.util.Objects;

public record ColumnModel(StableId id, String name, String nativeType, boolean nullable, String defaultValue) {
    public ColumnModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nativeType, "nativeType must not be null");
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
