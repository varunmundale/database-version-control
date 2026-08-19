package org.example.commands;

import org.example.adapters.DatabaseSchema;
import org.example.connectors.PostgresConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
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
    void startsSharedPostgresCreatesTheBranchDatabaseAndPrintsStatus() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(0, "container-id"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/orders");

        assertEquals("main", result.fromBranch());
        assertEquals("feature/orders", result.currentBranch());
        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals("feature_orders_postgres", result.databaseName());
        assertEquals(2, runner.commands.size());
        assertEquals(List.of("docker", "inspect", "--format", "{{.State.Running}}", "postgres-branches-scratchpad"), runner.commands.getFirst());
        assertEquals(List.of("docker", "run", "--detach", "--name", "postgres-branches-scratchpad"), runner.commands.get(1).subList(0, 5));
        assertEquals(List.of("--publish", "55432:5432"), runner.commands.get(1).subList(5, 7));

        assertEquals(1, connectorFactory.executed.size());
        assertEquals("postgres", connectorFactory.executed.get(0)[0]);
        assertEquals("CREATE DATABASE \"feature_orders_postgres\"", connectorFactory.executed.get(0)[1]);
        assertTrue(metadataStore.stagedChangesets.isEmpty());
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
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/payments");

        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals("feature_payments_postgres", result.databaseName());
        assertEquals(1, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("run")));
        assertEquals("CREATE DATABASE \"feature_payments_postgres\"", connectorFactory.executed.get(0)[1]);
    }

    @Test
    void recreatesTheBranchDatabaseByReplayingTheParentsCommitHistory() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        FakeMetadataStore metadataStore = new FakeMetadataStore();
        metadataStore.seedBranch("main");
        metadataStore.seedCommitHistory("main",
                "CREATE TABLE orders (id INT PRIMARY KEY);",
                "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;");
        BranchFork branchFork = new BranchFork(runner, connectorFactory, metadataStore);

        BranchForkResult result = branchFork.fork("main", "feature/orders");

        assertEquals("feature_orders_postgres", result.databaseName());
        assertEquals(3, connectorFactory.executed.size());
        assertEquals("postgres", connectorFactory.executed.get(0)[0]);
        assertEquals("CREATE DATABASE \"feature_orders_postgres\"", connectorFactory.executed.get(0)[1]);
        assertEquals("feature_orders_postgres", connectorFactory.executed.get(1)[0]);
        assertEquals("CREATE TABLE orders (id INT PRIMARY KEY);", connectorFactory.executed.get(1)[1]);
        assertEquals("feature_orders_postgres", connectorFactory.executed.get(2)[0]);
        assertEquals("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;", connectorFactory.executed.get(2)[1]);

        assertEquals(2, metadataStore.stagedChangesets.size());
        assertEquals("feature/orders", metadataStore.stagedChangesets.get(0)[0]);
        assertEquals("CREATE TABLE orders (id INT PRIMARY KEY);", metadataStore.stagedChangesets.get(0)[1]);
        assertEquals("feature/orders", metadataStore.stagedChangesets.get(1)[0]);
        assertEquals("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;", metadataStore.stagedChangesets.get(1)[1]);
        assertEquals(2, metadataStore.markAppliedCalls.size());
        assertEquals(2, metadataStore.commitCalls.size());
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
        private final Map<String, List<ChangeSet>> commitHistoryByBranch = new HashMap<>();
        private final List<String[]> createBranchCalls = new ArrayList<>();
        private final List<Object[]> stagedChangesets = new ArrayList<>();
        private final List<Long> markAppliedCalls = new ArrayList<>();
        private final List<Object[]> commitCalls = new ArrayList<>();
        private long nextId = 100;

        void seedBranch(String branch) {
            branches.add(branch);
        }

        void seedCommitHistory(String branch, String... ddls) {
            List<ChangeSet> history = new ArrayList<>();
            for (String ddl : ddls) {
                history.add(new ChangeSet(nextId++, branch, ddl, ChangesetStatus.COMMIT, Instant.now()));
            }
            commitHistoryByBranch.put(branch, history);
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
        public long stageChangeset(String branch, String ddl) {
            stagedChangesets.add(new Object[] {branch, ddl});
            return nextId++;
        }

        @Override
        public void markApplied(long changesetId) {
            markAppliedCalls.add(changesetId);
        }

        @Override
        public List<ChangeSet> changesetsForBranch(String branch) {
            return List.of();
        }

        @Override
        public List<ChangeSet> commitHistory(String branch) {
            return commitHistoryByBranch.getOrDefault(branch, List.of());
        }

        @Override
        public long commit(String branch, List<Long> changesetIds) {
            commitCalls.add(new Object[] {branch, changesetIds});
            return nextId++;
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
