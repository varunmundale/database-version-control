package org.example.connectors.mysql;

import org.example.connectors.JdbcConnector;

import java.sql.Connection;

/**
 * MySQL JDBC connector using the shared SQL execution abstraction. Opened via {@link MySqlConnections}.
 *
 * <p>Unlike {@link org.example.connectors.h2.H2Connector}, MySQL has real {@code CREATE DATABASE}/
 * {@code DROP DATABASE} statements of its own, so nothing here needs translating - {@code execute} is inherited
 * unchanged from {@link JdbcConnector}, the same way {@link org.example.connectors.postgres.PostgresConnector} is.
 */
public final class MySqlConnector extends JdbcConnector {
    public MySqlConnector(Connection connection) {
        super(connection);
    }
}
