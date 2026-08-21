package org.example.connectors.h2;

import org.example.connectors.JdbcConnections;
import org.example.connectors.JdbcConnector;

import java.sql.Connection;
import java.sql.SQLException;

/** A small JDBC connector for executing SQL against an H2 database. Opened via {@link H2Connections}. */
public final class H2Connector extends JdbcConnector {

    public H2Connector(Connection connection) {
        super(connection);
    }

    public static H2Connector inMemory(String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        return new H2Connector(JdbcConnections.openUrl(H2Connections.inMemoryUrl(databaseName)));
    }

}
