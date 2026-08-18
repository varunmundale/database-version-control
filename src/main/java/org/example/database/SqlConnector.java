package org.example.database;

import org.example.schema.DatabaseSchema;

import java.sql.SQLException;

/** Common database connector contract for SQL execution and schema introspection. */
public interface SqlConnector extends AutoCloseable {
    SqlExecutionResult execute(String sql) throws SQLException;

    DatabaseSchema inspectSchema() throws SQLException;

    @Override
    void close() throws SQLException;
}
