package org.example.connector;

import org.example.model.schema.DatabaseSchema;

import java.sql.Connection;
import java.sql.SQLException;

/** Converts a vendor's JDBC metadata into the common internal schema model. */
public interface SchemaParser {
    DatabaseSchema parse(Connection connection) throws SQLException;

    DatabaseSchema parse(Connection connection, String schema) throws SQLException;
}
