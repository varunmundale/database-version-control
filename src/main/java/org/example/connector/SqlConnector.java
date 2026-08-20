package org.example.connector;

import java.sql.SQLException;

/** Common database connector contract for SQL execution and transactions. */
public interface SqlConnector extends AutoCloseable {
    SqlExecutionResult execute(String sql) throws SQLException;

    /** Runs {@code work} against this connection as a single atomic transaction: committed if it returns, rolled back if it throws. */
    <T> T transaction(SqlTransaction<T> work) throws SQLException;

    @Override
    void close() throws SQLException;
}
