package org.example.replay;

import org.example.connector.SqlConnector;
import org.example.model.versioning.ChangeSet;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Recreates a database's schema from scratch by replaying committed changesets against a live connection, in order. */
public final class DatabaseRecreator {
    public void recreate(SqlConnector connector, List<ChangeSet> changesets) throws SQLException {
        Objects.requireNonNull(connector, "connector must not be null");
        Objects.requireNonNull(changesets, "changesets must not be null");
        for (ChangeSet changeset : changesets) {
            try {
                connector.execute(changeset.ddl());
            } catch (SQLException exception) {
                throw new SQLException("Could not replay changeset #" + changeset.id() + ": " + exception.getMessage(), exception);
            }
        }
    }
}
