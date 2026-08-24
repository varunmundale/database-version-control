package org.example.connectors;

import java.sql.SQLException;

/** Common database connector contract for SQL execution and transactions. */
public interface SqlConnector extends AutoCloseable {
    SqlExecutionResult execute(String sql) throws SQLException;

    /**
     * Runs {@code work} against this connection as a single atomic transaction: committed if it returns, rolled
     * back if it throws. Everything {@code work} runs must go through the connector it is handed, since the
     * transaction lives on that one connection.
     */
    <T> T transaction(SqlTransaction<T> work) throws SQLException;

    @Override
    void close() throws SQLException;
}
