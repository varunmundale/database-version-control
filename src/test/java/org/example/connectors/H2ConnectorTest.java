package org.example.connectors;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class H2ConnectorTest {
    @Test
    void executesStatementsAndReturnsRowsFromAnInMemoryDatabase() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test")) {
            SqlExecutionResult create = database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");
            SqlExecutionResult insert = database.execute("INSERT INTO people (id, name) VALUES (1, 'Ada'), (2, 'Grace')");
            SqlExecutionResult update = database.execute("UPDATE people SET name = 'Ada Lovelace' WHERE id = 1");
            SqlExecutionResult query = database.execute("SELECT id, name FROM people ORDER BY id");

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
    void rowsWithNullColumnsDoNotThrow() throws SQLException {
        try (H2Connector database = H2Connector.inMemory("connector_test_nulls")) {
            database.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");
            database.execute("INSERT INTO people (id, name) VALUES (1, NULL)");

            SqlExecutionResult query = database.execute("SELECT id, name FROM people");

            Map<String, Object> row = query.rows().getFirst();
            assertEquals(1, row.get("ID"));
            assertTrue(row.containsKey("NAME"));
            assertNull(row.get("NAME"));
        }
    }
}
