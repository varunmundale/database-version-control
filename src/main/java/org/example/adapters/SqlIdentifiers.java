package org.example.adapters;

import java.util.List;
import java.util.Locale;

/** Case-folding and cosmetic cleanup for identifiers and type names JSqlParser hands back, shared by every dialect. */
final class SqlIdentifiers {
    private SqlIdentifiers() {
    }

    static String normalize(String identifier) {
        return identifier.toLowerCase(Locale.ROOT);
    }

    static List<String> normalizeAll(List<String> identifiers) {
        return identifiers == null ? List.of() : identifiers.stream().map(SqlIdentifiers::normalize).toList();
    }

    /**
     * Collapses JSqlParser's rendering of e.g. {@code NUMERIC(10,2)} as {@code "NUMERIC (10, 2)"} back to a
     * canonical, whitespace-free form, and upper-cases it. Case matters here: a type is compared as a plain string
     * ({@link org.example.models.schema.ColumnModel#sameDefinitionAs}), so {@code varchar(100)} and
     * {@code VARCHAR(100)} must normalize identically or a case-only retype reads as a real change.
     */
    static String normalizeType(String dataType) {
        return dataType.strip()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)", ")")
                .toUpperCase(Locale.ROOT);
    }
}
