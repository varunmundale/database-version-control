package org.example.adapters.spi;

import org.example.adapters.DdlParser;
import org.example.adapters.DialectGrammar;
import org.example.adapters.SqlDdlParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a dialect name (e.g. {@code "postgresql"}) to the {@link DdlParser} that understands its DDL grammar - the
 * parser-side counterpart to {@link org.example.connectors.spi.ConnectorRegistry}, which does the same for JDBC
 * connections. Onboarding a new dialect whose grammar differs from the builtins only in vocabulary - not
 * behavior - means adding a {@link DialectGrammar} and registering a {@code new SqlDdlParser(grammar)} here; a
 * dialect that needs genuinely different parsing logic implements {@link DdlParser} directly instead. Either way,
 * no other code needs to change.
 */
public final class DdlParserRegistry {
    private final Map<String, DdlParser> parsers = new HashMap<>();

    public DdlParserRegistry register(String dialect, DdlParser parser) {
        parsers.put(Objects.requireNonNull(dialect, "dialect must not be null"),
                Objects.requireNonNull(parser, "parser must not be null"));
        return this;
    }

    public DdlParser get(String dialect) {
        DdlParser parser = parsers.get(dialect);
        if (parser == null) {
            throw new IllegalArgumentException("No DDL parser registered for dialect: " + dialect);
        }
        return parser;
    }

    public static DdlParserRegistry builtins() {
        return new DdlParserRegistry()
                .register("postgresql", new SqlDdlParser(DialectGrammar.postgresql()))
                .register("mysql", new SqlDdlParser(DialectGrammar.mysql()))
                .register("h2", new SqlDdlParser(DialectGrammar.h2()));
    }
}
