package org.example.repository;

import org.example.config.BranchDatabaseConfig;
import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.connectors.SqlTransaction;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchDatabaseRepositoryTest {
    private static final BranchDatabaseConfig CONFIG = BranchDatabaseConfig.getInstance();

    @Test
    void connectsToTheNamedDatabaseThroughTheConnectorFactory() throws Exception {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, connectorFactory);

        repository.connect("feature_orders_postgres").close();

        assertEquals(List.of("feature_orders_postgres"), connectorFactory.connections);
    }

    @Test
    void createsABranchDatabaseFromTheAdminDatabase() {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, connectorFactory);

        repository.createDatabase("feature_orders_postgres");

        assertEquals(List.of(repository.adminDatabase()), connectorFactory.connections);
        assertEquals(List.of(repository.adminDatabase() + " | CREATE DATABASE \"feature_orders_postgres\""),
                connectorFactory.executed);
    }

    @Test
    void appliesOneStatementAgainstTheBranchesOwnDatabase() {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, connectorFactory);

        repository.apply("feature_orders_postgres", "ALTER TABLE orders ADD COLUMN total INT;");

        assertEquals(List.of("feature_orders_postgres | ALTER TABLE orders ADD COLUMN total INT;"),
                connectorFactory.executed);
    }

    @Test
    void replaysEveryChangesetInOrderOverASingleConnection() {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, connectorFactory);

        repository.replay("feature_orders_postgres", List.of(
                changeset(1, "CREATE TABLE orders (id INT);"),
                changeset(2, "ALTER TABLE orders ADD COLUMN total INT;")));

        assertEquals(List.of("feature_orders_postgres | CREATE TABLE orders (id INT);",
                "feature_orders_postgres | ALTER TABLE orders ADD COLUMN total INT;"), connectorFactory.executed);
        assertEquals(1, connectorFactory.connections.size(), "the whole replay should reuse one connection");
        assertEquals(1, connectorFactory.transactions, "the whole replay should be one transaction");
        assertTrue(connectorFactory.committed);
    }

    @Test
    void namesTheChangesetThatFailedToReplay() {
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG,
                new RecordingConnectorFactory("ALTER TABLE orders ADD COLUMN total INT;"));

        RepositoryException exception = assertThrows(RepositoryException.class, () -> repository.replay(
                "feature_orders_postgres", List.of(
                        changeset(1, "CREATE TABLE orders (id INT);"),
                        changeset(2, "ALTER TABLE orders ADD COLUMN total INT;"))));

        assertTrue(exception.getMessage().contains("changeset #2"), exception.getMessage());
        assertTrue(exception.getMessage().contains("feature_orders_postgres"), exception.getMessage());
    }

    /** A half-replayed branch database is a schema no history describes, so the whole replay goes back. */
    @Test
    void aFailedReplayRollsBackTheStatementsThatAlreadySucceeded() {
        RecordingConnectorFactory connectorFactory =
                new RecordingConnectorFactory("ALTER TABLE orders ADD COLUMN total INT;");
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, connectorFactory);

        assertThrows(RepositoryException.class, () -> repository.replay("feature_orders_postgres", List.of(
                changeset(1, "CREATE TABLE orders (id INT);"),
                changeset(2, "ALTER TABLE orders ADD COLUMN total INT;"))));

        assertTrue(connectorFactory.rolledBack);
        assertFalse(connectorFactory.committed);
    }

    @Test
    void isNotReachableWhileTheServerRefusesConnections() {
        BranchDatabaseRepository unreachable = new BranchDatabaseRepository(CONFIG, settings -> {
            throw new SQLException("connection refused");
        });
        assertFalse(unreachable.isReachable());
        assertTrue(new BranchDatabaseRepository(CONFIG, new RecordingConnectorFactory()).isReachable());
    }

    private static ChangeSet changeset(long id, String ddl) {
        return new ChangeSet(id, "feature/orders", ddl, ChangesetStatus.COMMIT, Instant.now());
    }

    /** Records which databases were connected to and every statement run against them, and can fail one statement on demand. */
    private static final class RecordingConnectorFactory implements ConnectorFactory {
        private final List<String> connections = new ArrayList<>();
        private final List<String> executed = new ArrayList<>();
        private final String failingStatement;
        private int transactions;
        private boolean committed;
        private boolean rolledBack;

        private RecordingConnectorFactory() {
            this(null);
        }

        private RecordingConnectorFactory(String failingStatement) {
            this.failingStatement = failingStatement;
        }

        @Override
        public SqlConnector connect(ConnectionSettings settings) {
            connections.add(settings.database());
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) throws SQLException {
                    if (sql.equals(failingStatement)) {
                        throw new SQLException("syntax error");
                    }
                    executed.add(settings.database() + " | " + sql);
                    return new SqlExecutionResult(false, 0, List.of());
                }

                /** Stands in for what {@code JdbcConnector} does with a real connection, so a replay's outcome is observable. */
                @Override
                public <T> T transaction(SqlTransaction<T> work) throws SQLException {
                    transactions++;
                    try {
                        T result = work.execute(this);
                        committed = true;
                        return result;
                    } catch (SQLException | RuntimeException exception) {
                        rolledBack = true;
                        throw exception;
                    }
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
