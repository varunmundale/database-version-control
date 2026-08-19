package org.example.adapters;

import java.sql.Connection;
import java.sql.SQLException;

/** Converts a vendor's JDBC metadata into the common internal schema model. */
public interface SchemaParser {
    DatabaseSchema parse(Connection connection) throws SQLException;

    DatabaseSchema parse(Connection connection, String schema) throws SQLException;
}
