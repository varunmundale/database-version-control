package org.example.adapters;

import java.util.List;
import java.util.Locale;

/**
 * Parses a column's spec tokens (JSqlParser's flat {@code columnSpecs} list, everything after the type) into the
 * three properties {@link org.example.models.schema.ColumnModel} carries: {@code NOT NULL}, {@code DEFAULT}, and
 * an identity/auto-increment spec matched against the dialect's {@code identitySpecs}. Anything else (
 * {@code PRIMARY KEY}, {@code UNIQUE}, {@code REFERENCES}, {@code CHECK}) is a constraint wearing a column's
 * clothing and is rejected, naming the {@code ALTER TABLE ADD CONSTRAINT} form to write instead.
 */
record ColumnSpecs(boolean notNull, String defaultValue, String generatedAs) {

    static ColumnSpecs parse(List<String> specs, List<List<String>> identitySpecs, String columnName, String tableName) {
        if (specs == null || specs.isEmpty()) {
            return new ColumnSpecs(false, null, null);
        }
        Cursor cursor = new Cursor(specs);
        boolean notNull = false;
        String defaultValue = null;
        String generatedAs = null;

        while (cursor.hasNextToken()) {
            List<String> identity = matchIdentity(cursor, identitySpecs);
            if (identity != null) {
                generatedAs = String.join(" ", identity);
                cursor.skip(identity.size());
            } else if (matchNotNull(cursor)) {
                notNull = true;
            } else if (matchBareNull(cursor)) {
                // redundant NULL alongside DEFAULT/identity - accepted, carries no information
            } else if (matchDefault(cursor) instanceof String value) {
                defaultValue = value;
            } else {
                throw inlineConstraintError(cursor, columnName, tableName);
            }
        }
        return new ColumnSpecs(notNull, defaultValue, generatedAs);
    }

    /** {@code NOT NULL} - two tokens, always together; consumed only when both are present. */
    private static boolean matchNotNull(Cursor cursor) {
        if (!cursor.nextTokenIs("NOT") || !cursor.tokenAheadIs(1, "NULL")) {
            return false;
        }
        cursor.skip(2);
        return true;
    }

    /** A bare, redundant {@code NULL} - Postgres/H2/MySQL all accept it as a no-op alongside {@code DEFAULT}. */
    private static boolean matchBareNull(Cursor cursor) {
        if (!cursor.nextTokenIs("NULL")) {
            return false;
        }
        cursor.skip(1);
        return true;
    }

    /** {@code DEFAULT <value>} - the value is taken as exactly the one token that follows. Null means no match. */
    private static String matchDefault(Cursor cursor) {
        if (!cursor.nextTokenIs("DEFAULT") || cursor.tokenAhead(1) == null) {
            return null;
        }
        cursor.skip(1);
        return cursor.nextToken();
    }

    /** The longest of the dialect's identity spec sequences starting exactly here, or null if none matches. */
    private static List<String> matchIdentity(Cursor cursor, List<List<String>> identitySpecs) {
        List<String> longest = null;
        for (List<String> sequence : identitySpecs) {
            if (cursor.tokensAheadAre(sequence) && (longest == null || sequence.size() > longest.size())) {
                longest = sequence;
            }
        }
        return longest;
    }

    /** Names the constraint the column tried to declare, and suggests the statement that should carry it instead. */
    private static IllegalArgumentException inlineConstraintError(Cursor cursor, String columnName, String tableName) {
        String token = cursor.nextToken().toUpperCase(Locale.ROOT);
        boolean primaryKey = token.equals("PRIMARY");
        String kind = primaryKey ? "PRIMARY KEY" : token;
        String suggestion = switch (kind) {
            case "PRIMARY KEY" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_pkey PRIMARY KEY ("
                    + columnName + ")";
            case "UNIQUE" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                    + "_key UNIQUE (" + columnName + ")";
            case "REFERENCES" -> "ALTER TABLE " + tableName + " ADD CONSTRAINT " + tableName + "_" + columnName
                    + "_fkey FOREIGN KEY (" + columnName + ") REFERENCES <table> (<column>)";
            default -> "ALTER TABLE " + tableName + " ADD CONSTRAINT <name> " + kind + " (...)";
        };
        return new IllegalArgumentException("Column '" + columnName + "' declares " + kind
                + ", which is a constraint rather than a property of the column itself."
                + " Define the column by itself and add the constraint separately, e.g. " + suggestion + "."
                + " Only NOT NULL, DEFAULT and this dialect's identity/auto-increment spec may be written on a column.");
    }

    /** A read-only position into a token list - lookahead and advancement only. */
    private static final class Cursor {
        private final List<String> tokens;
        private int position;

        private Cursor(List<String> tokens) {
            this.tokens = tokens;
        }

        boolean hasNextToken() {
            return position < tokens.size();
        }

        /** The token {@code offset} positions ahead of the cursor, or null past the end - never throws. */
        String tokenAhead(int offset) {
            int index = position + offset;
            return index < tokens.size() ? tokens.get(index) : null;
        }

        boolean tokenAheadIs(int offset, String keyword) {
            return isKeyword(tokenAhead(offset), keyword);
        }

        boolean nextTokenIs(String keyword) {
            return tokenAheadIs(0, keyword);
        }

        /** Whether the tokens starting here spell out {@code sequence}, keyword by keyword. */
        boolean tokensAheadAre(List<String> sequence) {
            for (int offset = 0; offset < sequence.size(); offset++) {
                if (!tokenAheadIs(offset, sequence.get(offset))) {
                    return false;
                }
            }
            return true;
        }

        /** Consumes and returns the token at the cursor, advancing past it. */
        String nextToken() {
            return tokens.get(position++);
        }

        /** Advances the cursor past {@code count} tokens without returning them. */
        void skip(int count) {
            position += count;
        }

        private static boolean isKeyword(String token, String keyword) {
            return token != null && token.equalsIgnoreCase(keyword);
        }
    }
}
