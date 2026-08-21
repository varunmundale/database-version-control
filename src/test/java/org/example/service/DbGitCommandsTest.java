package org.example.service;

import org.example.core.forker.Forker;
import org.example.core.forker.docker.CommandResult;
import org.example.core.forker.docker.CommandRunner;
import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.connectors.SqlTransaction;
import org.example.models.tracking.TrackedDatabase;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.core.versioning.VersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitCommandsTest {
    @TempDir
    Path workingDirectory;

    private static final String MAIN_DATABASE = "app";

    private final List<DbGitCommandListener> listeners = new ArrayList<>();

    /** Each test drives the daemon directly rather than over a socket; the bound port is closed again in teardown. */
    private DbGitCommandListener listener(Forker forker) {
        try {
            DbGitCommandListener listener = new DbGitCommandListener(workingDirectory, forker, 0);
            listeners.add(listener);
            // main tracks a real database now, so point it at one before any test touches it.
            listener.execute("dbgit init --host localhost --port 5432 --database " + MAIN_DATABASE + " --user tester");
            return listener;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @AfterEach
    void closeListeners() throws IOException {
        for (DbGitCommandListener listener : listeners) {
            listener.close();
        }
    }

    @Test
    void createsABranchDatabaseAndWritesLocalDbGitState() throws IOException {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new NoOpConnectorFactory(), metadataStore));

        DbGitCommandResult result = dbgit.execute("dbgit checkout -b feature/orders");

        assertEquals(List.of("Switched to a new branch 'feature/orders'."), result.lines());
        assertEquals("feature/orders", Files.readString(workingDirectory.resolve(".dbgit/HEAD")).trim());
        assertEquals(List.of("feature/orders", "main"), metadataStore.branches());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void checksOutExistingBranchesAndListsAllBranches() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        DbGitCommandListener dbgit = listener(new Forker(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()));
        dbgit.execute("dbgit checkout -b feature/orders");

        DbGitCommandResult checkout = dbgit.execute("dbgit checkout main");
        DbGitCommandResult branchList = dbgit.execute("dbgit branch");

        assertEquals(List.of("Switched to branch 'main'."), checkout.lines());
        assertEquals(List.of("  feature/orders", "* main"), branchList.lines());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void addsACreateTableStatementBuildsTheInternalRepresentationAndAppliesTheChangeset() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));
        String ddl = "CREATE TABLE orders (\n  id INT NOT NULL,\n  name TEXT\n);";

        DbGitCommandResult result = dbgit.add(ddl);

        assertEquals(List.of("Applied changeset #1 for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals(MAIN_DATABASE, connectorFactory.connectedDatabase);
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
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        String alter = "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;";

        DbGitCommandResult result = dbgit.add(alter);

        assertEquals(List.of("Applied changeset #2 for branch 'main': table 'orders' now has 2 column(s)."), result.lines());
        assertEquals(alter, connectorFactory.executedSql);
        assertEquals(2, metadataStore.changesetsForBranch("main").size());
    }

    @Test
    void refusesToAlterAnUnknownTableWithoutTouchingTheDatabaseOrTheServer() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));

        assertThrows(IllegalArgumentException.class, () -> dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC;"));

        assertNull(connectorFactory.executedSql);
        assertTrue(metadataStore.changesetsForBranch("main").isEmpty());
    }

    @Test
    void commitsAllAppliedChangesetsIntoOneCommitWithForwardAndBackwardPointers() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;");

        DbGitCommandResult result = dbgit.execute("dbgit commit");

        assertEquals(List.of("Created commit #1 for branch 'main' with 2 changeset(s)."), result.lines());
        assertTrue(metadataStore.changesetsForBranch("main").stream().allMatch(changeset -> changeset.status() == ChangesetStatus.COMMIT));
        List<ChangeSet> history = metadataStore.commitHistory("main");
        assertEquals(2, history.size());
        assertEquals("CREATE TABLE orders (id INT NOT NULL);", history.get(0).ddl());
        assertEquals("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;", history.get(1).ddl());
    }

    @Test
    void reportsNothingToCommitWhenNoChangesetsAreApplied() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void onlyAppliedChangesetsAreEverCommittedTwice() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");

        DbGitCommandResult result = dbgit.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void reportsNoDifferencesWhenBranchesShareTheSameCommittedSchema() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");

        DbGitCommandResult result = dbgit.execute("dbgit diff main feature/orders");

        assertEquals(List.of("No differences between 'main' and 'feature/orders'."), result.lines());
    }

    @Test
    void diffPrintsATreeBringingBothSidesConflictingStatementsTogetherUnderTheConflictingColumn() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.add("ALTER TABLE orders ADD COLUMN total INT NOT NULL;");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout main");
        dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;");
        dbgit.execute("dbgit commit");

        DbGitCommandResult result = dbgit.execute("dbgit diff main feature/orders");

        // Both branches added a 'total' column - the same object by stable id - with different types, so
        // DatabaseDiff flags it as a real conflict and both branches' statements against it are grouped together
        // under their own labeled node in the tree.
        assertEquals(List.of(
                "main vs feature/orders",
                "- orders",
                "  |- total (conflicting)",
                "    |- > ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;",
                "    |- < ALTER TABLE orders ADD COLUMN total INT NOT NULL;"
        ), result.lines());
    }

    @Test
    void renamingAColumnOnOneBranchWhileTheOtherModifiesItIsFlaggedAsAConflict() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL, col1 NUMERIC(10,2));");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.add("ALTER TABLE orders RENAME COLUMN col1 TO col2;");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout main");
        dbgit.add("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;");
        dbgit.execute("dbgit commit");

        DbGitCommandResult result = dbgit.execute("dbgit diff main feature/orders");

        // Same underlying column: renamed on feature/orders, modified under its old name on main - one conflict,
        // not an unrelated "col1 disappeared" / "col2 appeared" pair, so both statements land under one node.
        assertTrue(result.lines().contains("  |- col1 (conflicting)"));
        assertTrue(result.lines().contains("    |- > ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;"));
        assertTrue(result.lines().contains("    |- < ALTER TABLE orders RENAME COLUMN col1 TO col2;"));
    }

    @Test
    void refusesToDiffAnUnknownBranch() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit diff main unknown-branch"));

        assertTrue(exception.getMessage().contains("Unknown branch"));
    }

    @Test
    void mergeAppliesTheOtherBranchsDivergedChangesetsAndRecordsATwoParentCommit() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"), new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        MultiRecordingConnectorFactory connectorFactory = new MultiRecordingConnectorFactory();
        DbGitCommandListener dbgit = listener(new Forker(runner, connectorFactory, metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        String alter = "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);";
        dbgit.add(alter);
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout main");

        DbGitCommandResult result = dbgit.execute("dbgit merge feature/orders");

        assertEquals(List.of(
                "Merged 'feature/orders' into 'main' as commit #3, applying 1 changeset(s).",
                "Validated via staging branch 'merge/main-feature/orders'."
        ), result.lines());
        assertTrue(metadataStore.branches().contains("merge/main-feature/orders"));

        List<ChangeSet> mainHistory = metadataStore.commitHistory("main");
        assertEquals(2, mainHistory.size());
        assertEquals("CREATE TABLE orders (id INT NOT NULL);", mainHistory.get(0).ddl());
        assertEquals(alter, mainHistory.get(1).ddl());

        // The alter ran once when originally staged on feature/orders, then again for real against both the
        // staging branch's scratch database and main's own live database while merging.
        List<String> databasesAlterRanAgainst = connectorFactory.executed.stream()
                .filter(pair -> pair[1].equals(alter))
                .map(pair -> pair[0])
                .toList();
        assertEquals(3, databasesAlterRanAgainst.size());
        assertTrue(databasesAlterRanAgainst.contains("feature_orders_postgres"));
        assertTrue(databasesAlterRanAgainst.contains(MAIN_DATABASE));
        assertTrue(databasesAlterRanAgainst.stream().anyMatch(database -> database.startsWith("merge_main-feature_orders")));

        assertEquals(List.of("No differences between 'main' and 'feature/orders'."),
                dbgit.execute("dbgit diff main feature/orders").lines());
    }

    @Test
    void mergeRejectsWhenBothBranchesConflictOnTheSameColumn() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.add("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout main");
        dbgit.add("ALTER TABLE orders ALTER COLUMN total TYPE INT;");
        dbgit.execute("dbgit commit");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> dbgit.execute("dbgit merge feature/orders"));

        assertTrue(exception.getMessage().contains("conflicting changes"));
        assertTrue(exception.getMessage().contains("orders"));
        assertTrue(exception.getMessage().contains("total"));
        // No staging branch or merge commit is created once a conflict is found.
        assertTrue(metadataStore.branches().stream().noneMatch(branch -> branch.startsWith("merge/")));
        assertEquals(2, metadataStore.commitHistory("main").size());
    }

    @Test
    void mergeReportsAlreadyUpToDateWhenTheOtherBranchHasNothingNew() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.execute("dbgit checkout main");

        DbGitCommandResult result = dbgit.execute("dbgit merge feature/orders");

        assertEquals(List.of("Already up to date."), result.lines());
        assertTrue(metadataStore.branches().stream().noneMatch(branch -> branch.startsWith("merge/")));
    }

    @Test
    void mergeRefusesAnUnknownBranch() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit merge unknown-branch"));

        assertTrue(exception.getMessage().contains("Unknown branch"));
    }

    @Test
    void mergeRefusesMergingABranchIntoItself() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit merge main"));

        assertTrue(exception.getMessage().contains("itself"));
    }

    @Test
    void forkedBranchesSharePriorCommitHistoryWithoutCreatingNewCommits() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DbGitCommandListener dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");

        dbgit.execute("dbgit checkout -b feature/orders");

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

    private static final class NoOpConnectorFactory implements ConnectorFactory {
        @Override
        public SqlConnector connect(ConnectionSettings settings) {
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) {
                    return new SqlExecutionResult(false, 0, List.of());
                }

                @Override
                public <T> T transaction(SqlTransaction<T> work) throws java.sql.SQLException {
                    return work.execute(this);
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class RecordingConnectorFactory implements ConnectorFactory {
        private String connectedDatabase;
        private String executedSql;

        @Override
        public SqlConnector connect(ConnectionSettings settings) {
            connectedDatabase = settings.database();
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) {
                    executedSql = sql;
                    return new SqlExecutionResult(false, 0, List.of());
                }

                @Override
                public <T> T transaction(SqlTransaction<T> work) throws java.sql.SQLException {
                    return work.execute(this);
                }

                @Override
                public void close() {
                }
            };
        }
    }

    /** Records every statement executed against every database, keyed by database name - unlike {@link RecordingConnectorFactory}, which only remembers the last one. */
    private static final class MultiRecordingConnectorFactory implements ConnectorFactory {
        private final List<String[]> executed = new ArrayList<>();

        @Override
        public SqlConnector connect(ConnectionSettings settings) {
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) {
                    executed.add(new String[] {settings.database(), sql});
                    return new SqlExecutionResult(false, 0, List.of());
                }

                @Override
                public <T> T transaction(SqlTransaction<T> work) throws java.sql.SQLException {
                    return work.execute(this);
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private static final class InMemoryMetadataStore implements VersioningService {
        private final Map<String, TrackedDatabase> trackedByBranch = new HashMap<>();

        @Override
        public TrackedDatabase track(String branch, String host, int port, String database, String user) {
            TrackedDatabase tracked = TrackedDatabase.of(branch, host, port, database, user);
            trackedByBranch.put(branch, tracked);
            return tracked;
        }

        @Override
        public Optional<TrackedDatabase> trackedDatabase(String branch) {
            return Optional.ofNullable(trackedByBranch.get(branch));
        }

        private final TreeSet<String> branches = new TreeSet<>(Set.of("main"));
        private final Map<Long, ChangeSet> changesetsById = new LinkedHashMap<>();
        private final Map<Long, List<Long>> changesetIdsByCommit = new LinkedHashMap<>();
        private final Map<Long, Long> parentCommitById = new HashMap<>();
        private final Map<Long, Long> secondParentCommitById = new HashMap<>();
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
            List<Long> order = new ArrayList<>();
            collectOrder(headCommitByBranch.get(branch), new LinkedHashSet<>(), order);
            List<ChangeSet> history = new ArrayList<>();
            for (long commitId : order) {
                for (long changesetId : changesetIdsByCommit.getOrDefault(commitId, List.of())) {
                    history.add(changesetsById.get(changesetId));
                }
            }
            return history;
        }

        /**
         * Walks a commit's ancestry via both parents (first parent's full history, then anything reachable only
         * through the second parent, then the commit itself), skipping a commit already visited so a common
         * ancestor shared by both parents of a merge is emitted exactly once, at its original position.
         */
        private void collectOrder(Long commitId, Set<Long> seen, List<Long> order) {
            if (commitId == null || seen.contains(commitId)) {
                return;
            }
            collectOrder(parentCommitById.get(commitId), seen, order);
            collectOrder(secondParentCommitById.get(commitId), seen, order);
            if (seen.add(commitId)) {
                order.add(commitId);
            }
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

        @Override
        public long createMergeCommit(String branch, String otherBranch) {
            long commitId = nextCommitId.getAndIncrement();
            parentCommitById.put(commitId, headCommitByBranch.get(branch));
            secondParentCommitById.put(commitId, headCommitByBranch.get(otherBranch));
            headCommitByBranch.put(branch, commitId);
            return commitId;
        }
    }

    @Test
    void initRecordsWhatMainTracksAndSaysSo() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute(
                "dbgit init --host db.internal --port 6543 --database app_prod --user dbgit --password secret");

        assertTrue(result.lines().getFirst().contains("app_prod@db.internal:6543"), result.lines().toString());
        assertTrue(result.lines().get(1).startsWith("Signature: connection_"), result.lines().toString());
    }

    @Test
    void initIsIdempotentForTheSameDatabase() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));
        String command = "dbgit init --host db.internal --port 6543 --database app_prod --user dbgit";

        String firstSignature = dbgit.execute(command).lines().get(1);
        DbGitCommandResult second = dbgit.execute(command);

        assertTrue(second.lines().getFirst().contains("already tracks"), second.lines().toString());
        assertEquals(firstSignature, second.lines().get(1), "the same database always signs the same");
    }

    @Test
    void initRepointsMainAtADifferentDatabase() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));
        dbgit.execute("dbgit init --host db.internal --port 6543 --database app_prod --user dbgit");

        DbGitCommandResult repointed = dbgit.execute(
                "dbgit init --host db.internal --port 6543 --database app_staging --user dbgit");

        assertTrue(repointed.lines().getFirst().contains("app_staging@db.internal:6543"), repointed.lines().toString());
        assertTrue(repointed.lines().getFirst().contains("was app_prod@db.internal:6543"), repointed.lines().toString());
    }

    @Test
    void initWritesCredentialsOnlyToTheLocalWorkspace() throws IOException {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        dbgit.execute("dbgit init --host db.internal --database app_prod --user dbgit --password hunter2");

        String localConfig = Files.readString(workingDirectory.resolve(".dbgit/config.json"));
        assertTrue(localConfig.contains("hunter2"), "the password belongs in local .dbgit state");
        assertTrue(localConfig.contains("app_prod"), localConfig);
    }

    @Test
    void mainsDdlGoesToTheTrackedDatabaseRatherThanTheScratchpad() {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), connectorFactory,
                new InMemoryMetadataStore()));
        dbgit.execute("dbgit init --host db.internal --port 6543 --database app_prod --user dbgit");

        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");

        assertEquals("app_prod", connectorFactory.connectedDatabase);
    }

    @Test
    void initRejectsMissingConnectionDetails() {
        DbGitCommandListener dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit init --host db.internal"));

        assertTrue(exception.getMessage().contains("required"), exception.getMessage());
    }
}
