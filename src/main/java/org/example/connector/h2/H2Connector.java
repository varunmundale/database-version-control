package org.example.connector.h2;

import org.example.connector.JdbcConnector;

import java.sql.SQLException;

/** A small JDBC connector for executing SQL against an H2 database. */
public final class H2Connector extends JdbcConnector {

    public H2Connector(String jdbcUrl) throws SQLException {
        super(jdbcUrl);
    }

    public static H2Connector inMemory(String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        return new H2Connector("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    }

}
