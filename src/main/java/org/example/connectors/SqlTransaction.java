package org.example.connectors;

import java.sql.SQLException;

/** A unit of work run atomically by {@link SqlConnector#transaction(SqlTransaction)}. */
@FunctionalInterface
public interface SqlTransaction<T> {
    T execute(SqlConnector connector) throws SQLException;
}
