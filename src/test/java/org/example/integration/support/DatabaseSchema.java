package org.example.integration.support;

import org.example.config.ConnectionSettings;
import org.example.connectors.h2.H2Connections;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a branch's database actually looks like, read back out of {@code INFORMATION_SCHEMA} directly - since
 * dbgit itself never introspects a database, this is the only way a test can confirm a fork, merge or reset put
 * the schema it claimed into the database it built. Every name is handed back lower-cased, since H2 folds
 * unquoted identifiers to upper case and assertions should read the way the DDL was written.
 */
public final class DatabaseSchema {
    public List<String> tables(String database) {
        return query(database, "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME");
    }

    /** A table's columns in the order the database holds them, which is the order the replayed DDL created them. */
    public List<String> columns(String database, String table) {
        return query(database, "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION", table);
    }

    /** A column's declared type, e.g. {@code numeric(14,4)} - what an {@code ALTER COLUMN ... TYPE} has to show up as. */
    public String columnType(String database, String table, String column) {
        List<String> types = query(database, "SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, column);
        return types.isEmpty() ? null : types.getFirst();
    }

    /**
     * A character column's declared length - what tells {@code VARCHAR(10)} from {@code VARCHAR(20)}, which
     * {@link #columnType} cannot, both being {@code character varying}.
     */
    public String columnLength(String database, String table, String column) {
        List<String> lengths = query(database, "SELECT CHARACTER_MAXIMUM_LENGTH FROM INFORMATION_SCHEMA.COLUMNS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? AND COLUMN_NAME = ?", table, column);
        return lengths.isEmpty() ? null : lengths.getFirst();
    }

    public List<String> constraints(String database, String table) {
        return query(database, "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? ORDER BY CONSTRAINT_NAME", table);
    }

    /**
     * A table's indexes. H2 backs every primary key and unique constraint with a generated index of its own, so
     * this lists more than the {@code CREATE INDEX} statements did - assert that it contains a name, not that it
     * equals a list.
     */
    public List<String> indexes(String database, String table) {
        return query(database, "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES"
                + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = ? ORDER BY INDEX_NAME", table);
    }

    private List<String> query(String database, String sql, String... arguments) {
        ConnectionSettings settings = new ConnectionSettings("localhost", 0, "", "", database);
        try (Connection connection = H2Connections.INSTANCE.open(settings);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) {
                statement.setString(index + 1, arguments[index].toUpperCase(Locale.ROOT));
            }
            try (ResultSet rows = statement.executeQuery()) {
                List<String> values = new ArrayList<>();
                while (rows.next()) {
                    values.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
                return values;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not read the schema of '" + database + "': "
                    + exception.getMessage(), exception);
        }
    }
}
