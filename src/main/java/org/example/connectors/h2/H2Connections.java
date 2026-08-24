package org.example.connectors.h2;

import org.example.config.ConnectionSettings;
import org.example.connectors.JdbcConnections;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The one definition of an H2 JDBC URL. Every H2 database dbgit opens is in-memory, named rather than addressed by
 * host/port, and lives as long as the JVM.
 *
 * <p>{@link #open} deliberately ignores {@code settings}' user/password rather than passing them to
 * {@link java.sql.DriverManager}: an in-memory H2 database adopts whatever credentials first connect and then
 * requires an exact match forever after, so a config-driven credential would have to agree byte-for-byte across
 * every caller, {@link H2Connector}'s own auxiliary connections included. Connecting credential-less sidesteps that.
 */
public final class H2Connections implements JdbcConnections {
    public static final H2Connections INSTANCE = new H2Connections();

    private H2Connections() {
    }

    @Override
    public String jdbcUrl(ConnectionSettings settings) {
        return inMemoryUrl(settings.database());
    }

    @Override
    public Connection open(ConnectionSettings settings) throws SQLException {
        return JdbcConnections.openUrl(jdbcUrl(settings));
    }

    public static String inMemoryUrl(String databaseName) {
        return "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
    }
}
