package org.example.connectors.h2;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2ConnectorTest {
    @Test
    void executesStatementsAndReturnsRowsFromAnInMemoryDatabase() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test")) {
            var create = database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");
            var insert = database.execute("INSERT INTO people (id, name) VALUES (1, 'Ada'), (2, 'Grace')");
            var update = database.execute("UPDATE people SET name = 'Ada Lovelace' WHERE id = 1");
            var query = database.execute("SELECT id, name FROM people ORDER BY id");

            assertFalse(create.hasResultSet());
            assertEquals(0, create.updateCount());
            assertEquals(2, insert.updateCount());
            assertEquals(1, update.updateCount());
            assertTrue(query.hasResultSet());
            assertEquals(-1, query.updateCount());
            assertEquals(List.of(
                    Map.of("ID", 1, "NAME", "Ada Lovelace"),
                    Map.of("ID", 2, "NAME", "Grace")
            ), query.rows());
        }
    }

    @Test
    void aTransactionCommitsEverythingItRanWhenTheWorkReturns() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test_commit")) {
            database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");

            int inserted = database.transaction(connector -> {
                connector.execute("INSERT INTO people (id, name) VALUES (1, 'Ada')");
                connector.execute("INSERT INTO people (id, name) VALUES (2, 'Grace')");
                return 2;
            });

            assertEquals(2, inserted);
            assertEquals(2, database.execute("SELECT id FROM people").rows().size());
        }
    }

    /** The property {@code BranchDatabaseRepository.replay} leans on: a failure part-way takes the earlier statements with it. */
    @Test
    void aTransactionRollsBackEverythingItRanWhenTheWorkThrows() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test_rollback")) {
            database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");

            assertThrows(IllegalStateException.class, () -> database.transaction(connector -> {
                connector.execute("INSERT INTO people (id, name) VALUES (1, 'Ada')");
                throw new IllegalStateException("work failed after the first insert");
            }));

            assertTrue(database.execute("SELECT id FROM people").rows().isEmpty(),
                    "the insert before the failure should have been rolled back");
        }
    }

    @Test
    void rowsWithNullColumnsDoNotThrow() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test_nulls")) {
            database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");
            database.execute("INSERT INTO people (id, name) VALUES (1, NULL)");

            var query = database.execute("SELECT id, name FROM people");

            Map<String, Object> row = query.rows().getFirst();
            assertEquals(1, row.get("ID"));
            assertTrue(row.containsKey("NAME"));
            assertNull(row.get("NAME"));
        }
    }
}
