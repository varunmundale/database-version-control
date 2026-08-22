package org.example.connectors.h2;

import org.example.config.ConnectionSettings;
import org.example.connectors.JdbcConnections;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * The one definition of an H2 JDBC URL. Every H2 database dbgit opens is in-memory, named rather than addressed by
 * host/port, and lives as long as the JVM.
 *
 * <p>{@link #open} deliberately ignores {@code settings}' user and password rather than passing them to
 * {@link java.sql.DriverManager} the way the default {@link JdbcConnections#open} would: H2 in-memory databases
 * have no real security boundary to enforce within one trusted JVM, and a fresh in-memory database adopts whatever
 * credentials first connect to it, then requires an exact match forever after (for that database's JVM lifetime).
 * A config-driven, non-blank user/password would mean every connection has to agree on it byte-for-byte, including
 * {@link H2Connector}'s own auxiliary connections when translating {@code CREATE}/{@code DROP DATABASE} - one
 * mismatch anywhere and H2 refuses the connection outright. Connecting credential-less everywhere sidesteps that
 * whole class of failure, since it is the one scheme every caller can agree on without coordinating.
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
