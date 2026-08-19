package org.example.branch;

import org.example.database.SqlConnector;
import org.example.database.SqlExecutionResult;
import org.example.schema.DatabaseSchema;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchForkTest {
    @Test
    void startsSharedPostgresForksTheParentBranchDatabasesAndPrintsStatus() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(0, "container-id"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        metadataStore.seedDatabase("main", new BranchDatabase("orders", "main_orders"));
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/orders");

        assertEquals("main", result.fromBranch());
        assertEquals("feature/orders", result.currentBranch());
        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals(List.of("feature_orders_postgres", "feature_orders_orders"), result.databaseNames());
        assertEquals(2, runner.commands.size());
        assertEquals(List.of("docker", "inspect", "--format", "{{.State.Running}}", "postgres-branches-scratchpad"), runner.commands.getFirst());
        assertEquals(List.of("docker", "run", "--detach", "--name", "postgres-branches-scratchpad"), runner.commands.get(1).subList(0, 5));
        assertEquals(List.of("--publish", "55432:5432"), runner.commands.get(1).subList(5, 7));

        assertEquals(2, connectorFactory.executed.size());
        assertEquals("postgres", connectorFactory.executed.get(0)[0]);
        assertEquals("CREATE DATABASE \"feature_orders_postgres\" WITH TEMPLATE \"postgres\"", connectorFactory.executed.get(0)[1]);
        assertEquals("postgres", connectorFactory.executed.get(1)[0]);
        assertEquals("CREATE DATABASE \"feature_orders_orders\" WITH TEMPLATE \"main_orders\"", connectorFactory.executed.get(1)[1]);

        assertEquals(1, metadataStore.createBranchCalls.size());
        assertEquals("feature/orders", metadataStore.createBranchCalls.getFirst()[0]);
        assertEquals("main", metadataStore.createBranchCalls.getFirst()[1]);
        assertEquals(1, metadataStore.recordDatabasesCalls.size());
        assertEquals("feature/orders", metadataStore.recordDatabasesCalls.getFirst()[0]);
        assertEquals(List.of(new BranchDatabase("orders", "feature_orders_orders")), metadataStore.recordDatabasesCalls.getFirst()[1]);
    }

    @Test
    void surfacesDockerFailures() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(125, "docker daemon is unavailable"));
        BranchFork branchFork = new BranchFork(runner, new RecordingConnectorFactory(), new FakeMetadataStore());

        BranchForkException exception = assertThrows(BranchForkException.class, () -> branchFork.fork("main", "feature/orders"));

        assertTrue(exception.getMessage().contains("start shared PostgreSQL container"));
    }

    @Test
    void reusesTheRunningSharedContainerForAnotherBranch() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        metadataStore.seedDatabase("main", new BranchDatabase("payments", "main_payments"));
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/payments");

        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals(List.of("feature_payments_postgres", "feature_payments_payments"), result.databaseNames());
        assertEquals(1, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("run")));
        assertEquals(2, connectorFactory.executed.size());
        assertEquals("CREATE DATABASE \"feature_payments_postgres\" WITH TEMPLATE \"postgres\"", connectorFactory.executed.get(0)[1]);
        assertEquals("CREATE DATABASE \"feature_payments_payments\" WITH TEMPLATE \"main_payments\"", connectorFactory.executed.get(1)[1]);
    }

    @Test
    void forksOnlyTheDefaultPostgresDatabaseWhenTheParentBranchHasNoneRegistered() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/empty");

        assertEquals(List.of("feature_empty_postgres"), result.databaseNames());
        assertEquals(1, connectorFactory.executed.size());
        assertEquals("CREATE DATABASE \"feature_empty_postgres\" WITH TEMPLATE \"postgres\"", connectorFactory.executed.get(0)[1]);
        assertEquals(1, metadataStore.createBranchCalls.size());
        assertEquals(1, metadataStore.recordDatabasesCalls.size());
        assertEquals(List.of(), metadataStore.recordDatabasesCalls.getFirst()[1]);
    }

    @Test
    void defaultDatabaseNameIsTheAdminDatabaseForMainAndAForkedCopyForOtherBranches() {
        BranchFork branchFork = new BranchFork(new RecordingRunner(), new RecordingConnectorFactory(), new FakeMetadataStore());

        assertEquals("postgres", branchFork.defaultDatabaseName("main"));
        assertEquals("feature_orders_postgres", branchFork.defaultDatabaseName("feature/orders"));
    }

    @Test
    void connectDelegatesToTheConnectorFactory() throws Exception {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        BranchFork branchFork = new BranchFork(new RecordingRunner(), connectorFactory, new FakeMetadataStore());

        branchFork.connect("feature_orders_postgres").close();

        assertEquals(List.of("feature_orders_postgres"), connectorFactory.connections);
    }

    @Test
    void refusesToForkWhenTheBranchAlreadyExists() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        metadataStore.seedBranch("feature/orders");
        BranchFork branchFork = new BranchFork(runner, new RecordingConnectorFactory(), metadataStore);

        BranchForkException exception = assertThrows(BranchForkException.class, () -> branchFork.fork("main", "feature/orders"));

        assertTrue(exception.getMessage().contains("Branch already exists: feature/orders"));
        assertTrue(runner.commands.isEmpty());
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

    private static final class FakeMetadataStore implements BranchMetadataStore {
        private final Set<String> branches = new LinkedHashSet<>();
        private final Map<String, List<BranchDatabase>> databasesByBranch = new HashMap<>();
        private final List<String[]> createBranchCalls = new ArrayList<>();
        private final List<Object[]> recordDatabasesCalls = new ArrayList<>();

        void seedBranch(String branch) {
            branches.add(branch);
        }

        void seedDatabase(String branch, BranchDatabase database) {
            databasesByBranch.computeIfAbsent(branch, key -> new ArrayList<>()).add(database);
        }

        @Override
        public List<String> branches() {
            return List.copyOf(branches);
        }

        @Override
        public boolean createBranch(String branchName, String forkedFrom) {
            createBranchCalls.add(new String[] {branchName, forkedFrom});
            return branches.add(branchName);
        }

        @Override
        public List<BranchDatabase> databasesForBranch(String branch) {
            return databasesByBranch.getOrDefault(branch, List.of());
        }

        @Override
        public void recordDatabases(String branch, List<BranchDatabase> databases) {
            recordDatabasesCalls.add(new Object[] {branch, databases});
            databasesByBranch.put(branch, databases);
        }

        @Override
        public void recordChangeset(ChangeSet changeset) {
        }

        @Override
        public List<String> changesetsForBranch(String branch) {
            return List.of();
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
