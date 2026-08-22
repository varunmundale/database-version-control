package org.example.integration.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * What a branch's database actually looks like, read back out of it.
 *
 * <p>This is the half of an integration test the unit tests cannot reach. dbgit never introspects a database - it
 * writes DDL into one and replays history into another - so the only way to show that a fork, a merge or a reset
 * put the schema it claimed into the schema it built is to go and look. Everything here reads
 * {@code INFORMATION_SCHEMA} directly, deliberately bypassing dbgit's own model.
 *
 * <p>H2 folds unquoted identifiers to upper case; every name is handed back lower-cased, so assertions read the
 * way the DDL that created them was written.
 */
public final class DatabaseSchema {
    private final H2Databases databases;

    public DatabaseSchema(H2Databases databases) {
        this.databases = Objects.requireNonNull(databases, "databases must not be null");
    }

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
        try (Connection connection = databases.open(database);
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
