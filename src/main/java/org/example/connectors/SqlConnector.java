package org.example.connectors;

import java.sql.SQLException;

/** Common database connector contract for SQL execution and transactions. */
public interface SqlConnector extends AutoCloseable {
    SqlExecutionResult execute(String sql) throws SQLException;

    /**
     * Runs {@code work} against this connection as a single atomic transaction: committed if it returns, rolled
     * back if it throws. Everything {@code work} runs must go through the connector it is handed, since the
     * transaction lives on that one connection.
     *
     * <p>This is the branch-database half of dbgit's transaction handling, and the only one written against raw
     * JDBC. The metadata store's half is not a second implementation of this: those repositories are jOOQ, so
     * {@code MetadataDatabase.transaction} delegates to jOOQ's own transaction API and binds it to the calling
     * thread, which is what lets stateless repository singletons join it without being handed a connection.
     */
    <T> T transaction(SqlTransaction<T> work) throws SQLException;

    @Override
    void close() throws SQLException;
}
