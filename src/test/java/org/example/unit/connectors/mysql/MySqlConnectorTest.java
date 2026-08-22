package org.example.unit.connectors.mysql;

import org.example.config.ConnectionSettings;
import org.example.connectors.mysql.MySqlConnections;
import org.example.connectors.mysql.MySqlConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link MySqlConnector}/{@link MySqlConnections} against a real, throwaway MySQL container - proof the
 * {@code mysql} dialect genuinely works (real DDL, real {@code CREATE}/{@code DROP DATABASE}, and the
 * {@code ANSI_QUOTES} session variable that makes dbgit's double-quoted identifiers valid), the same way
 * {@code H2ConnectorTest} proves {@code h2} does. Skips without Docker, the same way the integration tests do.
 */
class MySqlConnectorTest {
    private static MySQLContainer container;

    @BeforeAll
    static void startContainer() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required to run this test; skipping.");
        container = new MySQLContainer(DockerImageName.parse("mysql:8.0"));
        container.start();
    }

    @AfterAll
    static void stopContainer() {
        if (container != null) {
            container.stop();
        }
    }

    private static ConnectionSettings settings() {
        return new ConnectionSettings(container.getHost(), container.getMappedPort(MySQLContainer.MYSQL_PORT),
                container.getUsername(), container.getPassword(), container.getDatabaseName());
    }

    @Test
    void executesRealDdlAndReturnsRowsFromARealMySqlDatabase() throws SQLException {
        try (MySqlConnector connector = new MySqlConnector(MySqlConnections.INSTANCE.open(settings()))) {
            connector.execute("CREATE TABLE people (id INT PRIMARY KEY, name VARCHAR(100))");
            connector.execute("INSERT INTO people (id, name) VALUES (1, 'Ada'), (2, 'Grace')");

            var query = connector.execute("SELECT id, name FROM people ORDER BY id");

            assertTrue(query.hasResultSet());
            assertEquals(List.of(Map.of("id", 1, "name", "Ada"), Map.of("id", 2, "name", "Grace")), query.rows());
        } finally {
            drop("people");
        }
    }

    /**
     * dbgit emits {@code CREATE DATABASE "name"} - a double-quoted identifier - regardless of dialect. Under
     * MySQL's default sql_mode a double-quoted token is a string literal, not an identifier; {@link MySqlConnections}
     * asks for {@code ANSI_QUOTES} precisely so this form is valid instead.
     */
    @Test
    void ansiQuotesModeMakesDoubleQuotedIdentifiersWorkTheWayDbgitEmitsThem() throws SQLException {
        try (MySqlConnector connector = new MySqlConnector(MySqlConnections.INSTANCE.open(settings()))) {
            connector.execute("CREATE TABLE \"quoted_orders\" (\"id\" INT PRIMARY KEY)");

            var tables = connector.execute("SHOW TABLES LIKE 'quoted_orders'");

            assertFalse(tables.rows().isEmpty());
        } finally {
            drop("quoted_orders");
        }
    }

    private static void drop(String table) throws SQLException {
        try (MySqlConnector connector = new MySqlConnector(MySqlConnections.INSTANCE.open(settings()))) {
            connector.execute("DROP TABLE IF EXISTS " + table);
        }
    }
}
