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
     * JSqlParser renders e.g. {@code NUMERIC(10,2)} as {@code "NUMERIC (10, 2)"}; this collapses that back to a
     * canonical, whitespace-free form around parens without touching multi-word type names like
     * {@code DOUBLE PRECISION}.
     */
    static String normalizeType(String dataType) {
        return dataType.strip()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)", ")");
    }
}
