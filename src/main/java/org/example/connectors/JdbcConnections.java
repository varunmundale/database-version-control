package org.example.connectors;

import org.example.config.ConnectionSettings;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;

/**
 * Where one vendor's JDBC URL is built, and the only place a connection is opened from one. {@link ConnectorFactory}
 * wraps what this returns in a {@link SqlConnector}; callers that have to hand a plain {@link Connection} to another
 * library - jOOQ, in the metadata store - use it directly instead.
 */
@FunctionalInterface
public interface JdbcConnections {
    /** Where the database lives. Credentials are passed separately by {@link #open}, so they never need URL escaping. */
    String jdbcUrl(ConnectionSettings settings);

    default Connection open(ConnectionSettings settings) throws SQLException {
        Objects.requireNonNull(settings, "settings must not be null");
        return DriverManager.getConnection(jdbcUrl(settings), settings.user(), settings.password());
    }

    /** For the handful of URLs that carry no credentials at all, such as an anonymous in-memory H2 database. */
    static Connection openUrl(String jdbcUrl) throws SQLException {
        return DriverManager.getConnection(Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"));
    }
}
