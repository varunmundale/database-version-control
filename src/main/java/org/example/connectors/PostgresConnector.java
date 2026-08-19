package org.example.connectors;

import org.example.adapters.PostgresSchemaParser;

import java.sql.SQLException;

/** PostgreSQL JDBC connector using the shared SQL and schema-inspection abstraction. */
public final class PostgresConnector extends JdbcConnector {
    public PostgresConnector(String jdbcUrl) throws SQLException {
        super(jdbcUrl, new PostgresSchemaParser());
    }
}
