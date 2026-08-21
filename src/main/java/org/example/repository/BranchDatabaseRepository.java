package org.example.repository;

import org.example.config.BranchDatabaseConfig;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.spi.ConnectorRegistry;
import org.example.models.versioning.ChangeSet;

import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * The branches scratchpad: the one shared PostgreSQL container every branch's real database is forked into.
 * Everything dbgit does to a branch database goes through here - create it, run a statement against it, replay a
 * whole history into it, check the server is up - so no caller opens a connection to it for itself.
 *
 * <p>Sibling of the metadata repositories in this package, and the other half of the two-database split dbgit runs
 * on: {@link BranchMetadataRepository} and friends record what a branch's schema *should* be, this applies it for
 * real.
 */
public final class BranchDatabaseRepository {
    private final BranchDatabaseConfig config;
    private final ConnectorFactory connectorFactory;

    public BranchDatabaseRepository(BranchDatabaseConfig config, ConnectorFactory connectorFactory) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
    }

    /** The scratchpad described by {@code dbgit.properties}; tests build their own against a stub connector instead. */
    public static BranchDatabaseRepository getInstance() {
        return Holder.INSTANCE;
    }

    /** The container's own database, which every branch database is created from - and which {@code main} uses directly. */
    public String adminDatabase() {
        return config.adminDatabase();
    }

    public SqlConnector connect(String database) throws SQLException {
        return connectorFactory.connect(config.connectionTo(Objects.requireNonNull(database, "database must not be null")));
    }

    /** Whether the scratchpad server is accepting connections yet - the probe container startup polls on. */
    public boolean isReachable() {
        try (SqlConnector ignored = connect(adminDatabase())) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    public void createDatabase(String database) {
        execute(adminDatabase(), "CREATE DATABASE \"" + database + "\"", "create branch database '" + database + "'");
    }

    /** Runs one DDL statement against a branch's database. */
    public void apply(String database, String ddl) {
        execute(database, ddl, "apply DDL to database '" + database + "'");
    }

    /** Replays a history into a branch's database over a single connection, in order, stopping at the first failure. */
    public void replay(String database, List<ChangeSet> changesets) {
        Objects.requireNonNull(changesets, "changesets must not be null");
        try (SqlConnector connector = connect(database)) {
            for (ChangeSet changeset : changesets) {
                try {
                    connector.execute(changeset.ddl());
                } catch (SQLException exception) {
                    throw new RepositoryException("Could not replay changeset #" + changeset.id() + " against database '"
                            + database + "': " + exception.getMessage(), exception);
                }
            }
        } catch (SQLException exception) {
            throw new RepositoryException("Could not replay history against database '" + database + "': "
                    + exception.getMessage(), exception);
        }
    }

    private void execute(String database, String sql, String operation) {
        try (SqlConnector connector = connect(database)) {
            connector.execute(sql);
        } catch (SQLException exception) {
            throw new RepositoryException("Could not " + operation + ": " + exception.getMessage(), exception);
        }
    }

    /** Defers reading configuration and building a connector until a branch database is actually touched. */
    private static final class Holder {
        private static final BranchDatabaseRepository INSTANCE = create();

        private static BranchDatabaseRepository create() {
            BranchDatabaseConfig config = BranchDatabaseConfig.getInstance();
            return new BranchDatabaseRepository(config, ConnectorRegistry.builtins().get(config.dialect()));
        }
    }
}
