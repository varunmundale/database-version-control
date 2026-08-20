package org.example.replay;

import org.example.connector.SqlConnector;
import org.example.connector.h2.H2Connector;
import org.example.model.schema.DatabaseSchema;
import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rebuilds a schema from scratch by replaying DDL changesets, in order, through a disposable in-memory database -
 * reusing the same JDBC-based {@link org.example.connector.SchemaParser} used for real databases instead of a
 * separate hand-written DDL parser. {@code DATABASE_TO_LOWER=TRUE} keeps identifier casing aligned with
 * PostgreSQL's (H2 upper-cases unquoted identifiers by default).
 */
public final class SchemaReplayer {

    /** Replays committed history into a schema snapshot, e.g. for {@code dbgit diff}. */
    public Map<String, TableModel> replay(List<ChangeSet> changesets) {
        try (SqlConnector connector = openScratchDatabase()) {
            applyHistory(connector, changesets);
            return tablesByName(inspect(connector));
        } catch (SQLException exception) {
            throw new ReplayException("Could not open scratch replay database: " + exception.getMessage(), exception);
        }
    }

    /**
     * Replays {@code history}, then validates and applies {@code statement} on top of it, returning the single
     * table the statement created or modified. Used to preview {@code dbgit add} before it ever touches the
     * branch's real database.
     */
    public TableModel preview(List<ChangeSet> history, String statement) {
        try (SqlConnector connector = openScratchDatabase()) {
            applyHistory(connector, history);
            DatabaseSchema before = inspect(connector);
            try {
                connector.execute(statement);
            } catch (SQLException exception) {
                throw new IllegalArgumentException("Could not apply DDL statement: " + exception.getMessage(), exception);
            }
            return changedTable(before, inspect(connector), statement);
        } catch (SQLException exception) {
            throw new ReplayException("Could not open scratch replay database: " + exception.getMessage(), exception);
        }
    }

    private void applyHistory(SqlConnector connector, List<ChangeSet> changesets) {
        for (ChangeSet changeset : changesets) {
            if (changeset.status() != ChangesetStatus.APPLIED && changeset.status() != ChangesetStatus.COMMIT) {
                continue;
            }
            try {
                connector.execute(changeset.ddl());
            } catch (SQLException exception) {
                throw new ReplayException("Could not replay changeset #" + changeset.id() + ": " + exception.getMessage(), exception);
            }
        }
    }

    private static DatabaseSchema inspect(SqlConnector connector) {
        try {
            return connector.inspectSchema();
        } catch (SQLException exception) {
            throw new ReplayException("Could not inspect replayed schema: " + exception.getMessage(), exception);
        }
    }

    private static SqlConnector openScratchDatabase() throws SQLException {
        String name = "replay_" + UUID.randomUUID().toString().replace("-", "");
        return new H2Connector("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
    }

    private static Map<String, TableModel> tablesByName(DatabaseSchema schema) {
        Map<String, TableModel> tables = new LinkedHashMap<>();
        schema.tables().forEach(table -> tables.put(table.name(), table));
        return tables;
    }

    private static TableModel changedTable(DatabaseSchema before, DatabaseSchema after, String statement) {
        Map<String, TableModel> beforeByName = tablesByName(before);
        for (TableModel table : after.tables()) {
            TableModel previous = beforeByName.get(table.name());
            if (previous == null || !previous.columns().equals(table.columns())) {
                return table;
            }
        }
        throw new IllegalArgumentException("DDL statement did not add or modify any table's columns: " + statement);
    }
}
