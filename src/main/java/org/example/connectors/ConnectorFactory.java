package org.example.connectors;

import org.example.config.ConnectionSettings;

import java.sql.SQLException;

/** Opens a connection to one database for a given vendor dialect. */
@FunctionalInterface
public interface ConnectorFactory {
    SqlConnector connect(ConnectionSettings settings) throws SQLException;
}
