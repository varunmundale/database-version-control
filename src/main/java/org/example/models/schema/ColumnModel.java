package org.example.models.schema;

import java.util.Objects;

/**
 * A column, either fully identified as part of a {@link TableModel} or, via {@link #unassigned}, just as a
 * vendor's DDL grammar described it - before it has been given the real stable id it takes from the table it
 * belongs to, with {@link #identifiedIn}.
 */
public record ColumnModel(StableId id, String name, String nativeType, boolean nullable, String defaultValue,
                           String generatedAs) implements SchemaElement<ColumnModel> {
    private static final StableId UNASSIGNED_ID = new StableId("column_unassigned");

    public ColumnModel {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(nativeType, "nativeType must not be null");
    }

    /** A column as parsed from DDL, with no stable id of its own yet - see {@link #identifiedIn}. */
    public static ColumnModel unassigned(String name, String nativeType, boolean nullable, String defaultValue) {
        return unassigned(name, nativeType, nullable, defaultValue, null);
    }

    /** As {@link #unassigned(String, String, boolean, String)}, plus this dialect's identity/auto-increment spec (e.g. {@code "AUTO_INCREMENT"}), or null if the column has none. */
    public static ColumnModel unassigned(String name, String nativeType, boolean nullable, String defaultValue, String generatedAs) {
        return new ColumnModel(UNASSIGNED_ID, name, nativeType, nullable, defaultValue, generatedAs);
    }

    private ColumnModel withId(StableId id) {
        return new ColumnModel(id, name, nativeType, nullable, defaultValue, generatedAs);
    }

    /** This column as a member of {@code tableId}, taking the stable id it will keep for the rest of its life. */
    public ColumnModel identifiedIn(StableId tableId) {
        return withId(StableId.forColumn(tableId, name));
    }

    /**
     * The same column under a new name. The stable id is deliberately carried over, which is what makes a rename
     * read as one column that changed rather than one dropped and another added.
     */
    public ColumnModel renamedTo(String newName) {
        return new ColumnModel(id, newName, nativeType, nullable, defaultValue, generatedAs);
    }

    /** The same column with a new type; name and stable id unchanged. */
    public ColumnModel retyped(String newNativeType) {
        return new ColumnModel(id, name, newNativeType, nullable, defaultValue, generatedAs);
    }

    /** True when this column's type, nullability, default and identity/auto-increment spec match another's, independent of name or id. */
    public boolean sameDefinitionAs(ColumnModel other) {
        return nativeType.equals(other.nativeType) && nullable == other.nullable
                && Objects.equals(defaultValue, other.defaultValue) && Objects.equals(generatedAs, other.generatedAs);
    }

    /** Renders this column's type, nullability, default and identity/auto-increment spec for {@code dbgit diff}'s conflict messages, e.g. {@code "NUMERIC(10,2) NOT NULL DEFAULT 0"}. */
    public String definition() {
        return nativeType + (nullable ? "" : " NOT NULL") + (defaultValue == null ? "" : " DEFAULT " + defaultValue)
                + (generatedAs == null ? "" : " " + generatedAs);
    }

    /** True when this column, matched by id with another, differs in name or definition - i.e. there is something to report. */
    public boolean differsFrom(ColumnModel other) {
        return !name.equals(other.name) || !sameDefinitionAs(other);
    }
}
