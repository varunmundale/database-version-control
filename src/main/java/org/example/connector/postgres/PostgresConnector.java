package org.example.connector.postgres;

import org.example.connector.JdbcConnector;

import java.sql.SQLException;

/** PostgreSQL JDBC connector using the shared SQL execution abstraction. */
public final class PostgresConnector extends JdbcConnector {
    public PostgresConnector(String jdbcUrl) throws SQLException {
        super(jdbcUrl);
    }
}
