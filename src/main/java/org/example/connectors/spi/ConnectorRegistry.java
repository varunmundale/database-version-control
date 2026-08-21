package org.example.connectors.spi;

import org.example.connectors.ConnectorFactory;
import org.example.connectors.JdbcConnections;
import org.example.connectors.SqlConnector;
import org.example.connectors.h2.H2Connections;
import org.example.connectors.h2.H2Connector;
import org.example.connectors.postgres.PostgresConnections;
import org.example.connectors.postgres.PostgresConnector;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Maps a dialect name (e.g. {@code "postgresql"}) to the two halves of vendor support: the {@link JdbcConnections}
 * that knows how to open a connection for it, and the {@link SqlConnector} that wraps one. Onboarding a new vendor
 * means implementing both and registering them here - no other code needs to change.
 */
public final class ConnectorRegistry {
    private final Map<String, Dialect> dialects = new HashMap<>();

    public ConnectorRegistry register(String dialect, JdbcConnections connections, Function<Connection, SqlConnector> connector) {
        dialects.put(Objects.requireNonNull(dialect, "dialect must not be null"),
                new Dialect(Objects.requireNonNull(connections, "connections must not be null"),
                        Objects.requireNonNull(connector, "connector must not be null")));
        return this;
    }

    /** Opens a connection for the dialect and wraps it, so no caller has to know either half. */
    public ConnectorFactory get(String dialect) {
        Dialect registered = dialect(dialect);
        return settings -> registered.connector().apply(registered.connections().open(settings));
    }

    /** The raw connection opener, for callers handing connections to another library rather than to a {@link SqlConnector}. */
    public JdbcConnections connections(String dialect) {
        return dialect(dialect).connections();
    }

    public static ConnectorRegistry builtins() {
        return new ConnectorRegistry()
                .register("postgresql", PostgresConnections.INSTANCE, PostgresConnector::new)
                .register("h2", H2Connections.INSTANCE, H2Connector::new);
    }

    private Dialect dialect(String dialect) {
        Dialect registered = dialects.get(dialect);
        if (registered == null) {
            throw new IllegalArgumentException("No connector registered for dialect: " + dialect);
        }
        return registered;
    }

    private record Dialect(JdbcConnections connections, Function<Connection, SqlConnector> connector) {
    }
}
