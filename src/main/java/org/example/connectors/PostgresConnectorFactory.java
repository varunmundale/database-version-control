package org.example.connectors;


import java.sql.SQLException;

/** Opens a direct connection to one database in the shared PostgreSQL instance. */
@FunctionalInterface
public interface PostgresConnectorFactory {
    SqlConnector connect(String database) throws SQLException;
}
