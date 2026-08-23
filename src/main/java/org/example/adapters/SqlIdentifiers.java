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
     * {@code DOUBLE PRECISION}, and upper-cases it - a type is SQL keywords, and {@code varchar(100)} and
     * {@code VARCHAR(100)} describe the same column.
     *
     * <p>The case matters because a type is compared as a string
     * ({@link org.example.models.schema.ColumnModel#sameDefinitionAs}), and every such comparison is load-bearing: a branch that retyped a column and then
     * compensated back to what the shared history declared, but wrote the type in a different case, still read as
     * having changed it - so the merge it had just fixed was refused as a conflict. Identifiers get the opposite
     * treatment ({@link #normalize}) because an unquoted identifier folds to lower case in Postgres.
     */
    static String normalizeType(String dataType) {
        return dataType.strip()
                .replaceAll("\\s*\\(\\s*", "(")
                .replaceAll("\\s*,\\s*", ",")
                .replaceAll("\\s*\\)", ")")
                .toUpperCase(Locale.ROOT);
    }
}
