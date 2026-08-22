package org.example.models.schema;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;

/** A deterministic identifier derived from an object's logical schema path. */
public record StableId(String value) {
    public StableId {
        Objects.requireNonNull(value, "value must not be null");
    }

    /** A table's identity: its schema-qualified name. */
    public static StableId forTable(String schema, String tableName) {
        return of("table", schema + "." + tableName);
    }

    /** A column's identity: its name under the table that owns it, which is what carries it through a rename. */
    public static StableId forColumn(StableId tableId, String columnName) {
        return of("column", tableId.value() + "." + columnName);
    }

    /** A constraint's identity: its name under the table that owns it. */
    public static StableId forConstraint(StableId tableId, String constraintName) {
        return of("constraint", tableId.value() + "." + constraintName);
    }

    /** An index's identity: its name under the table that owns it. */
    public static StableId forIndex(StableId tableId, String indexName) {
        return of("index", tableId.value() + "." + indexName);
    }

    public static StableId of(String kind, String logicalPath) {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(logicalPath, "logicalPath must not be null");
        String canonicalPath = kind + ":" + logicalPath.toLowerCase(Locale.ROOT);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonicalPath.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : hash) {
                hex.append(String.format("%02x", value));
            }
            return new StableId(kind + "_" + hex.substring(0, 24));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
