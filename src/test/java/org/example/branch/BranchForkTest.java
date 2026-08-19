package org.example.branch;

import org.example.database.SqlConnector;
import org.example.database.SqlExecutionResult;
import org.example.schema.DatabaseSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchForkTest {
    @Test
    void startsSharedPostgresCreatesABranchDatabaseAndPrintsStatus() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(0, "container-id"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchFork branchFork = new BranchFork(runner, connectorFactory);

        BranchForkResult result = branchFork.fork("main", "feature/orders");

        assertEquals("main", result.fromBranch());
        assertEquals("feature/orders", result.currentBranch());
        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertTrue(result.databaseName().startsWith("branch_feature_orders_"));
        assertEquals(2, runner.commands.size());
        assertEquals(List.of("docker", "inspect", "--format", "{{.State.Running}}", "postgres-branches-scratchpad"), runner.commands.getFirst());
        assertEquals(List.of("docker", "run", "--detach", "--name", "postgres-branches-scratchpad"), runner.commands.get(1).subList(0, 5));
        assertEquals(List.of("--publish", "55432:5432"), runner.commands.get(1).subList(5, 7));

        assertEquals(3, connectorFactory.connections.size());
        assertEquals("postgres", connectorFactory.connections.get(0));
        assertEquals("postgres", connectorFactory.executed.get(0)[0]);
        assertEquals("CREATE DATABASE \"" + result.databaseName() + "\"", connectorFactory.executed.get(0)[1]);
        assertEquals(result.databaseName(), connectorFactory.executed.get(1)[0]);
        assertTrue(connectorFactory.executed.get(1)[1].contains("VALUES ('feature/orders', 'main')"));
    }

    @Test
    void surfacesDockerFailures() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(125, "docker daemon is unavailable"));
        BranchFork branchFork = new BranchFork(runner, new RecordingConnectorFactory());

        BranchForkException exception = assertThrows(BranchForkException.class, () -> branchFork.fork("main", "feature/orders"));

        assertTrue(exception.getMessage().contains("start shared PostgreSQL container"));
    }

    @Test
    void reusesTheRunningSharedContainerForAnotherBranch() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchFork branchFork = new BranchFork(runner, connectorFactory);

        BranchForkResult result = branchFork.fork("main", "feature/payments");

        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals(1, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("run")));
        assertEquals("CREATE DATABASE \"" + result.databaseName() + "\"", connectorFactory.executed.get(0)[1]);
    }

    private static final class RecordingRunner implements CommandRunner {
        private final List<CommandResult> results;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingRunner(CommandResult... results) {
            this.results = new ArrayList<>(List.of(results));
        }

        @Override
        public CommandResult run(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            return results.removeFirst();
        }
    }

    private static final class RecordingConnectorFactory implements PostgresConnectorFactory {
        private final List<String> connections = new ArrayList<>();
        private final List<String[]> executed = new ArrayList<>();

        @Override
        public SqlConnector connect(String database) {
            connections.add(database);
            return new RecordingConnector(database);
        }

        private final class RecordingConnector implements SqlConnector {
            private final String database;

            private RecordingConnector(String database) {
                this.database = database;
            }

            @Override
            public SqlExecutionResult execute(String sql) {
                executed.add(new String[] {database, sql});
                return new SqlExecutionResult(false, 0, List.of());
            }

            @Override
            public DatabaseSchema inspectSchema() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void close() {
            }
        }
    }
}
