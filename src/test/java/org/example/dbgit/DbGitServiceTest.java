package org.example.dbgit;

import org.example.branch.BranchDatabase;
import org.example.branch.BranchFork;
import org.example.branch.BranchMetadataStore;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final class InMemoryMetadataStore implements BranchMetadataStore {
        private final Map<String, List<BranchDatabase>> databasesByBranch = new TreeMap<>(Map.of("main", List.of()));

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
    }
}
