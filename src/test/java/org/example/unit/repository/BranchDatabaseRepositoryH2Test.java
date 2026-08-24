package org.example.unit.repository;

import org.example.config.BranchDatabaseConfig;
import org.example.connectors.h2.H2Connections;
import org.example.connectors.spi.ConnectorRegistry;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.repository.BranchDatabaseRepository;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BranchDatabaseRepository} built over the real {@code "h2"} connector factory - proof {@code dialect: "h2"}
 * genuinely works, not just something a test double simulates. Passes the factory directly rather than flipping the
 * shared {@code BranchDatabaseConfig} singleton, which other tests (e.g. {@code ForkerTest}) read for real.
 */
class BranchDatabaseRepositoryH2Test {
    private static final BranchDatabaseConfig CONFIG = BranchDatabaseConfig.getInstance();

    @Test
    void createsReplaysAndDropsARealH2BranchDatabase() throws Exception {
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, ConnectorRegistry.builtins().get("h2"));
        String database = "h2_dialect_capability_test";

        repository.createDatabase(database);
        repository.replay(database, List.of(
                changeset(1, database, "CREATE TABLE orders (id INT PRIMARY KEY);"),
                changeset(2, database, "ALTER TABLE orders ADD COLUMN total INT;")));

        assertEquals(List.of("ID", "TOTAL"), columnsOf(database, "ORDERS"));

        repository.dropDatabase(database);

        assertTrue(columnsOf(database, "ORDERS").isEmpty(), "dropDatabase should have emptied the H2 database");
    }

    @Test
    void isReachableConnectsForRealAgainstH2() {
        BranchDatabaseRepository repository = new BranchDatabaseRepository(CONFIG, ConnectorRegistry.builtins().get("h2"));

        assertTrue(repository.isReachable(), "an in-memory H2 admin database is always reachable once connected");
    }

    private static ChangeSet changeset(long id, String branch, String ddl) {
        return new ChangeSet(id, branch, ddl, ChangesetStatus.COMMIT, Instant.now());
    }

    private static List<String> columnsOf(String database, String table) throws Exception {
        try (Connection connection = H2Connections.INSTANCE.open(CONFIG.connectionTo(database));
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS"
                     + " WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_NAME = '" + table + "' ORDER BY ORDINAL_POSITION")) {
            List<String> columns = new ArrayList<>();
            while (rows.next()) {
                columns.add(rows.getString(1));
            }
            return columns;
        }
    }
}
