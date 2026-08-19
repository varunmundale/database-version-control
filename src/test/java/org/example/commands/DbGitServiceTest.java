package org.example.commands;

import org.example.connectors.PostgresConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.adapters.DatabaseSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

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
    void addsACreateTableStatementBuildsTheInternalRepresentationAndAppliesTheChangeset() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), connectorFactory, metadataStore));
        String ddl = "CREATE TABLE orders (\n  id INT PRIMARY KEY,\n  name TEXT\n);";

        DbGitCommandResult result = service.add(ddl);

        assertEquals(List.of("Applied changeset #1 for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals("postgres", connectorFactory.connectedDatabase);
        assertEquals(ddl, connectorFactory.executedSql);
        List<ChangeSet> changesets = metadataStore.changesetsForBranch("main");
        assertEquals(1, changesets.size());
        assertEquals(ChangesetStatus.APPLIED, changesets.getFirst().status());
        assertEquals(ddl, changesets.getFirst().ddl());
    }

    @Test
    void appliesAnAlterStatementOnTopOfThePreviouslyAppliedInternalRepresentation() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), connectorFactory, metadataStore));
        service.add("CREATE TABLE orders (id INT PRIMARY KEY);");
        String alter = "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;";

        DbGitCommandResult result = service.add(alter);

        assertEquals(List.of("Applied changeset #2 for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals(alter, connectorFactory.executedSql);
        assertEquals(2, metadataStore.changesetsForBranch("main").size());
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

    @Test
    void commitsAllAppliedChangesetsIntoOneCommitWithForwardAndBackwardPointers() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        service.add("CREATE TABLE orders (id INT PRIMARY KEY);");
        service.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;");

        DbGitCommandResult result = service.execute("dbgit commit");

        assertEquals(List.of("Created commit #1 for branch 'main' with 2 changeset(s)."), result.lines());
        assertTrue(metadataStore.changesetsForBranch("main").stream().allMatch(changeset -> changeset.status() == ChangesetStatus.COMMIT));
        List<ChangeSet> history = metadataStore.commitHistory("main");
        assertEquals(2, history.size());
        assertEquals("CREATE TABLE orders (id INT PRIMARY KEY);", history.get(0).ddl());
        assertEquals("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;", history.get(1).ddl());
    }

    @Test
    void reportsNothingToCommitWhenNoChangesetsAreApplied() {
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        DbGitCommandResult result = service.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void onlyAppliedChangesetsAreEverCommittedTwice() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        service.add("CREATE TABLE orders (id INT PRIMARY KEY);");
        service.execute("dbgit commit");

        DbGitCommandResult result = service.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void forkedBranchesSharePriorCommitHistoryWithoutCreatingNewCommits() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(runner, new RecordingConnectorFactory(), metadataStore));
        service.add("CREATE TABLE orders (id INT PRIMARY KEY);");
        service.execute("dbgit commit");

        service.execute("dbgit checkout -b feature/orders");

        List<ChangeSet> mainHistory = metadataStore.commitHistory("main");
        List<ChangeSet> featureHistory = metadataStore.commitHistory("feature/orders");
        assertEquals(1, mainHistory.size());
        assertEquals(mainHistory, featureHistory);
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
        private final TreeSet<String> branches = new TreeSet<>(Set.of("main"));
        private final Map<Long, ChangeSet> changesetsById = new LinkedHashMap<>();
        private final Map<Long, List<Long>> changesetIdsByCommit = new LinkedHashMap<>();
        private final Map<Long, Long> parentCommitById = new HashMap<>();
        private final Map<String, Long> headCommitByBranch = new HashMap<>();
        private final AtomicLong nextChangesetId = new AtomicLong(1);
        private final AtomicLong nextCommitId = new AtomicLong(1);

        @Override
        public List<String> branches() {
            return List.copyOf(branches);
        }

        @Override
        public boolean createBranch(String branchName, String forkedFrom) {
            boolean added = branches.add(branchName);
            if (added && forkedFrom != null) {
                headCommitByBranch.put(branchName, headCommitByBranch.get(forkedFrom));
            }
            return added;
        }

        @Override
        public long stageChangeset(String branch, String ddl) {
            long id = nextChangesetId.getAndIncrement();
            changesetsById.put(id, new ChangeSet(id, branch, ddl, ChangesetStatus.PENDING, Instant.now()));
            return id;
        }

        @Override
        public void markApplied(long changesetId) {
            ChangeSet changeset = changesetsById.get(changesetId);
            changesetsById.put(changesetId,
                    new ChangeSet(changeset.id(), changeset.branch(), changeset.ddl(), ChangesetStatus.APPLIED, changeset.appliedAt()));
        }

        @Override
        public List<ChangeSet> changesetsForBranch(String branch) {
            return changesetsById.values().stream().filter(changeset -> changeset.branch().equals(branch)).toList();
        }

        @Override
        public List<ChangeSet> commitHistory(String branch) {
            List<Long> chain = new ArrayList<>();
            Long current = headCommitByBranch.get(branch);
            while (current != null) {
                chain.add(current);
                current = parentCommitById.get(current);
            }
            Collections.reverse(chain);
            List<ChangeSet> history = new ArrayList<>();
            for (long commitId : chain) {
                for (long changesetId : changesetIdsByCommit.getOrDefault(commitId, List.of())) {
                    history.add(changesetsById.get(changesetId));
                }
            }
            return history;
        }

        @Override
        public long commit(String branch, List<Long> changesetIds) {
            List<Long> applied = changesetIds.stream()
                    .filter(id -> changesetsById.get(id).status() == ChangesetStatus.APPLIED)
                    .toList();
            long commitId = nextCommitId.getAndIncrement();
            parentCommitById.put(commitId, headCommitByBranch.get(branch));
            headCommitByBranch.put(branch, commitId);
            changesetIdsByCommit.put(commitId, applied);
            for (long id : applied) {
                ChangeSet changeset = changesetsById.get(id);
                changesetsById.put(id, new ChangeSet(changeset.id(), changeset.branch(), changeset.ddl(), ChangesetStatus.COMMIT, changeset.appliedAt()));
            }
            return commitId;
        }
    }
}
