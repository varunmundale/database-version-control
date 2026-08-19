package org.example.dbgit;

import org.example.branch.BranchDatabase;
import org.example.branch.BranchFork;
import org.example.branch.BranchMetadataStore;
import org.example.branch.ChangeSet;
import org.example.branch.CommandResult;
import org.example.branch.CommandRunner;
import org.example.branch.PostgresConnectorFactory;
import org.example.database.SqlConnector;
import org.example.database.SqlExecutionResult;
import org.example.schema.DatabaseSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitServiceTest {
    @TempDir
    Path workingDirectory;

    @Test
    void createsABranchDatabaseAndWritesLocalDbGitState() throws IOException {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(runner, new NoOpConnectorFactory(), metadataStore));

        DbGitCommandResult result = service.execute("dbgit checkout -b feature/orders");

        assertEquals(List.of("Switched to a new branch 'feature/orders'."), result.lines());
        assertEquals("feature/orders", Files.readString(workingDirectory.resolve(".dbgit/HEAD")).trim());
        assertEquals(List.of("feature/orders", "main"), metadataStore.branches());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void checksOutExistingBranchesAndListsAllBranches() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()));
        service.execute("dbgit checkout -b feature/orders");

        DbGitCommandResult checkout = service.execute("dbgit checkout main");
        DbGitCommandResult branchList = service.execute("dbgit branch");

        assertEquals(List.of("Switched to branch 'main'."), checkout.lines());
        assertEquals(List.of("  feature/orders", "* main"), branchList.lines());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void addsACreateTableStatementBuildsTheInternalRepresentationAndRecordsTheRawChangeset() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), connectorFactory, metadataStore));
        String ddl = "CREATE TABLE orders (\n  id INT PRIMARY KEY,\n  name TEXT\n);";

        DbGitCommandResult result = service.add(ddl);

        assertEquals(List.of("Recorded changeset for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals("postgres", connectorFactory.connectedDatabase);
        assertEquals(ddl, connectorFactory.executedSql);
        assertEquals(List.of(ddl), metadataStore.changesetsForBranch("main"));
    }

    @Test
    void appliesAnAlterStatementOnTopOfThePreviouslyRecordedInternalRepresentation() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), connectorFactory, metadataStore));
        service.add("CREATE TABLE orders (id INT PRIMARY KEY);");
        String alter = "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;";

        DbGitCommandResult result = service.add(alter);

        assertEquals(List.of("Recorded changeset for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals(alter, connectorFactory.executedSql);
        assertEquals(List.of("CREATE TABLE orders (id INT PRIMARY KEY);", alter), metadataStore.changesetsForBranch("main"));
    }

    @Test
    void refusesToAlterAnUnknownTableWithoutTouchingTheDatabaseOrTheServer() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), connectorFactory, metadataStore));

        assertThrows(IllegalArgumentException.class, () -> service.add("ALTER TABLE orders ADD COLUMN total NUMERIC;"));

        assertNull(connectorFactory.executedSql);
        assertTrue(metadataStore.changesetsForBranch("main").isEmpty());
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

    private static final class NoOpConnectorFactory implements PostgresConnectorFactory {
        @Override
        public SqlConnector connect(String database) {
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) {
                    return new SqlExecutionResult(false, 0, List.of());
                }

                @Override
                public DatabaseSchema inspectSchema() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class RecordingConnectorFactory implements PostgresConnectorFactory {
        private String connectedDatabase;
        private String executedSql;

        @Override
        public SqlConnector connect(String database) {
            connectedDatabase = database;
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) {
                    executedSql = sql;
                    return new SqlExecutionResult(false, 0, List.of());
                }

                @Override
                public DatabaseSchema inspectSchema() {
                    return new DatabaseSchema("postgresql", "public", List.of());
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class InMemoryMetadataStore implements BranchMetadataStore {
        private final Map<String, List<BranchDatabase>> databasesByBranch = new TreeMap<>(Map.of("main", List.of()));
        private final Map<String, List<String>> changesetsByBranch = new HashMap<>();

        @Override
        public List<String> branches() {
            return List.copyOf(databasesByBranch.keySet());
        }

        @Override
        public boolean createBranch(String branchName, String forkedFrom) {
            return databasesByBranch.putIfAbsent(branchName, List.of()) == null;
        }

        @Override
        public List<BranchDatabase> databasesForBranch(String branch) {
            return databasesByBranch.getOrDefault(branch, List.of());
        }

        @Override
        public void recordDatabases(String branch, List<BranchDatabase> databases) {
            databasesByBranch.put(branch, databases);
        }

        @Override
        public void recordChangeset(ChangeSet changeset) {
            changesetsByBranch.computeIfAbsent(changeset.branch(), key -> new ArrayList<>()).add(changeset.ddl());
        }

        @Override
        public List<String> changesetsForBranch(String branch) {
            return changesetsByBranch.getOrDefault(branch, List.of());
        }
    }
}
