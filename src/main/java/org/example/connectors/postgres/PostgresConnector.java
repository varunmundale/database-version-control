package org.example.connectors.postgres;

import org.example.connectors.JdbcConnector;

import java.sql.Connection;

/** PostgreSQL JDBC connector using the shared SQL execution abstraction. Opened via {@link PostgresConnections}. */
public final class PostgresConnector extends JdbcConnector {
    public PostgresConnector(Connection connection) {
        super(connection);
    }
}
