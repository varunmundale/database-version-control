package org.example.connectors.spi;

import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.h2.H2Connector;
import org.example.connectors.postgres.PostgresConnector;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a dialect name (e.g. {@code "postgresql"}) to the {@link ConnectorFactory} that knows how to open
 * connections for it. Onboarding a new vendor means implementing a {@link org.example.connectors.SqlConnector}
 * (typically by extending {@link org.example.connectors.JdbcConnector}), then registering a factory for it here -
 * no other code needs to change.
 */
public final class ConnectorRegistry {
    private final Map<String, ConnectorFactory> factories = new HashMap<>();

    public ConnectorRegistry register(String dialect, ConnectorFactory factory) {
        factories.put(Objects.requireNonNull(dialect, "dialect must not be null"),
                Objects.requireNonNull(factory, "factory must not be null"));
        return this;
    }

    public ConnectorFactory get(String dialect) {
        ConnectorFactory factory = factories.get(dialect);
        if (factory == null) {
            throw new IllegalArgumentException("No connector registered for dialect: " + dialect);
        }
        return factory;
    }

    public static ConnectorRegistry builtins() {
        return new ConnectorRegistry()
                .register("postgresql", settings -> new PostgresConnector(postgresJdbcUrl(settings)))
                .register("h2", settings -> new H2Connector(h2JdbcUrl(settings)));
    }

    private static String postgresJdbcUrl(ConnectionSettings settings) {
        return "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?user=" + settings.user() + "&password=" + settings.password();
    }

    private static String h2JdbcUrl(ConnectionSettings settings) {
        return "jdbc:h2:mem:" + settings.database() + ";DB_CLOSE_DELAY=-1";
    }
}
