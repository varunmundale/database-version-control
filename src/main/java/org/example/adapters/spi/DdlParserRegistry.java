package org.example.adapters.spi;

import org.example.adapters.DdlParser;
import org.example.adapters.h2.H2DdlParser;
import org.example.adapters.mysql.MySqlDdlParser;
import org.example.adapters.postgres.PostgresDdlParser;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a dialect name (e.g. {@code "postgresql"}) to the {@link DdlParser} that understands its DDL grammar - the
 * parser-side counterpart to {@link org.example.connectors.spi.ConnectorRegistry}, which does the same for JDBC
 * connections. Onboarding a new dialect means implementing {@link DdlParser} (usually by extending
 * {@code SqlDdlParser}) and registering it here - no other code needs to change.
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
                .register("postgresql", new PostgresDdlParser())
                .register("mysql", new MySqlDdlParser())
                .register("h2", new H2DdlParser());
    }
}
