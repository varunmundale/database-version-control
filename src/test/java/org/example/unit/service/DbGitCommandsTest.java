package org.example.unit.service;


import org.example.service.DbGitCommandListener;
import org.example.service.DbGitCommandResult;
import org.example.core.forker.Forker;
import org.example.config.ConcurrencyConfig;
import org.example.core.locking.TestBranchLocks;
import org.example.core.forker.docker.CommandResult;
import org.example.core.forker.docker.CommandRunner;
import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.connectors.SqlTransaction;
import org.example.config.TrackedDatabaseConfig;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitEntry;
import org.example.models.versioning.CommitMetadata;
import org.example.models.versioning.CommitParents;
import org.example.core.versioning.VersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitCommandsTest {
    @TempDir
    Path workingDirectory;

    private static final String MAIN_DATABASE = "app";

    private final List<DbGitCommandListener> listeners = new ArrayList<>();

    /**
     * Each test drives the daemon directly rather than over a socket, through a {@link DaemonSession} standing in
     * for the client - the daemon keeps no current branch of its own, so someone has to hold one.
     */
    private DaemonSession listener(Forker forker) {
        try {
            DbGitCommandListener listener = new DbGitCommandListener(forker, 0,
                    ConcurrencyConfig.getInstance(), TestBranchLocks.inMemory());
            listeners.add(listener);
            DaemonSession session = new DaemonSession(listener, "tester");
            // main tracks a real database now, so point it at one before any test touches it.
            session.execute("dbgit init --host localhost --port 5432 --database " + MAIN_DATABASE + " --user tester");
            return session;
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
        DaemonSession dbgit = listener(new Forker(runner, new NoOpConnectorFactory(), metadataStore));

        DbGitCommandResult result = dbgit.execute("dbgit checkout -b feature/orders");

        assertEquals(List.of("Switched to a new branch 'feature/orders'."), result.lines());
        // HEAD is the caller's now, not a file the daemon owns - the session tracks it the way the client does.
        assertEquals("feature/orders", dbgit.branch());
        assertEquals(List.of("feature/orders", "main"), metadataStore.branches());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void checksOutExistingBranchesAndListsAllBranches() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        DaemonSession dbgit = listener(new Forker(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()));
        dbgit.execute("dbgit checkout -b feature/orders");

        DbGitCommandResult checkout = dbgit.execute("dbgit checkout main");
        DbGitCommandResult branchList = dbgit.execute("dbgit branch");

        assertEquals(List.of("Switched to branch 'main'."), checkout.lines());
        assertEquals(List.of("  feature/orders", "* main"), branchList.lines());
        assertEquals(1, runner.commands.size());
    }

    @Test
    void helpListsEveryCommandsUsage() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new NoOpConnectorFactory(), new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute("dbgit help");

        List<String> lines = result.lines();
        assertEquals("dbgit commands:", lines.getFirst());
        assertEquals("Run 'dbgit help <command>' for detail on one command.", lines.getLast());
        assertTrue(lines.stream().anyMatch(line -> line.contains("dbgit commit")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("dbgit merge <branch>")));
    }

    @Test
    void helpWithACommandNamePrintsJustThatCommandsUsage() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new NoOpConnectorFactory(), new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute("dbgit help commit");

        assertEquals(List.of("dbgit commit [-m <message>] [--author <name>]",
                "Folds the current branch's applied changesets into one new commit, chained onto the branch's "
                        + "HEAD. --author overrides the default author (whoever the daemon runs as)."),
                result.lines());
    }

    @Test
    void helpRejectsAnUnknownCommandName() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new NoOpConnectorFactory(), new InMemoryMetadataStore()));

        assertThrows(IllegalArgumentException.class, () -> dbgit.execute("dbgit help nonsense"));
    }

    @Test
    void addsACreateTableStatementBuildsTheInternalRepresentationAndAppliesTheChangeset() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));
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
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));
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
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), connectorFactory, metadataStore));

        assertThrows(IllegalArgumentException.class, () -> dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC;"));

        assertNull(connectorFactory.executedSql);
        assertTrue(metadataStore.changesetsForBranch("main").isEmpty());
    }

    @Test
    void commitsAllAppliedChangesetsIntoOneCommitWithForwardAndBackwardPointers() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void onlyAppliedChangesetsAreEverCommittedTwice() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");

        DbGitCommandResult result = dbgit.execute("dbgit commit");

        assertEquals(List.of("Nothing to commit for branch 'main'."), result.lines());
    }

    @Test
    void reportsNoDifferencesWhenBranchesShareTheSameCommittedSchema() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit diff main unknown-branch"));

        assertTrue(exception.getMessage().contains("Unknown branch"));
    }

    @Test
    void mergeAppliesTheOtherBranchsDivergedChangesetsAndRecordsATwoParentCommit() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"), new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        MultiRecordingConnectorFactory connectorFactory = new MultiRecordingConnectorFactory();
        DaemonSession dbgit = listener(new Forker(runner, connectorFactory, metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout -b feature/orders");
        String alter = "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);";
        dbgit.add(alter);
        dbgit.execute("dbgit commit");
        dbgit.execute("dbgit checkout main");

        DbGitCommandResult result = dbgit.execute("dbgit merge feature/orders");

        assertEquals("Merged 'feature/orders' into 'main' as commit #3, applying 1 changeset(s).",
                result.lines().getFirst());
        // The staging branch is named per attempt, so two overlapping merges of the same pair cannot collide.
        assertTrue(result.lines().get(1).startsWith("Validated via staging branch 'merge/main-feature/orders-"),
                result.lines().toString());
        // ...and it exists only to prove the replay, so it is gone once the merge is settled.
        assertTrue(metadataStore.branches().stream().noneMatch(branch -> branch.startsWith("merge/")),
                metadataStore.branches().toString());

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
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
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
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit merge unknown-branch"));

        assertTrue(exception.getMessage().contains("Unknown branch"));
    }

    @Test
    void mergeRefusesMergingABranchIntoItself() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit merge main"));

        assertTrue(exception.getMessage().contains("itself"));
    }

    @Test
    void forkedBranchesSharePriorCommitHistoryWithoutCreatingNewCommits() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(runner, new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit");

        dbgit.execute("dbgit checkout -b feature/orders");

        List<ChangeSet> mainHistory = metadataStore.commitHistory("main");
        List<ChangeSet> featureHistory = metadataStore.commitHistory("feature/orders");
        assertEquals(1, mainHistory.size());
        assertEquals(mainHistory, featureHistory);
    }

    @Test
    void logShowsEachCommitsIdAuthorDateMessageAndChangesetsNewestFirst() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit -m create the orders table --author ada");
        dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        dbgit.execute("dbgit commit -m add a total column --author grace");

        List<String> lines = dbgit.execute("dbgit log").lines();

        assertEquals("Branch 'main'", lines.getFirst());
        assertTrue(lines.contains("Working set: clean."), lines.toString());
        // Newest commit first, each entry carrying who wrote it, why, and what it folded in.
        int second = lines.indexOf("commit #2");
        int first = lines.indexOf("commit #1");
        assertTrue(second > 0 && first > second, "newest commit should come first: " + lines);
        assertEquals(List.of(
                "commit #2",
                "Author:     grace",
                "Message:    add a total column",
                "Changesets:",
                "  #2 ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);"),
                withoutDates(lines.subList(second, first)));
        assertEquals(List.of(
                "commit #1",
                "Author:     ada",
                "Message:    create the orders table",
                "Changesets:",
                "  #1 CREATE TABLE orders (id INT NOT NULL);"),
                withoutDates(lines.subList(first, lines.size())));
    }

    @Test
    void logListsUncommittedChangesetsAsTheWorkingSetAboveTheCommits() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit -m first");
        dbgit.add("ALTER TABLE orders ADD COLUMN note TEXT;");

        List<String> lines = dbgit.execute("dbgit log").lines();

        assertEquals(List.of(
                "Working set (1 uncommitted changeset(s)):",
                "  #2 [APPLIED] ALTER TABLE orders ADD COLUMN note TEXT;"),
                lines.subList(2, 4));
        assertTrue(lines.indexOf("commit #1") > 3, lines.toString());
    }

    @Test
    void logCollapsesMultiLineDdlAndSaysSoWhenABranchHasNoCommits() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));

        assertEquals(List.of("Branch 'main'", "", "Working set: clean.", "No commits on branch 'main' yet."),
                dbgit.execute("dbgit log").lines());

        dbgit.add("CREATE TABLE orders (\n  id INT NOT NULL,\n  name TEXT\n);");

        // Stored exactly as written, newlines included - but a log is read line by line.
        assertTrue(dbgit.execute("dbgit log").lines()
                .contains("  #1 [APPLIED] CREATE TABLE orders ( id INT NOT NULL, name TEXT );"));
    }

    @Test
    void commitDefaultsTheAuthorToWhoeverTheDaemonRunsAsAndAcceptsAnEmptyMessage() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");

        dbgit.execute("dbgit commit");

        List<String> lines = dbgit.execute("dbgit log").lines();
        assertTrue(lines.contains("Author:     " + System.getProperty("user.name")), lines.toString());
        assertTrue(lines.contains("Message:    (none)"), lines.toString());
    }

    @Test
    void resetRewindsABranchDroppingItsWorkingSetAndRebuildingItsDatabaseFromTheTruncatedHistory() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"), new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        MultiRecordingConnectorFactory connectorFactory = new MultiRecordingConnectorFactory();
        DaemonSession dbgit = listener(new Forker(runner, connectorFactory, metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit -m first table");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        dbgit.execute("dbgit commit -m add total");
        dbgit.add("ALTER TABLE orders ADD COLUMN note TEXT;");

        DbGitCommandResult result = dbgit.execute("dbgit reset #1");

        assertEquals(List.of(
                "Branch 'feature/orders' reset to commit #1.",
                "Dropped 1 working changeset(s); replayed 1 committed changeset(s) into a rebuilt database.",
                "Schema now has 1 table(s)."), result.lines());

        // History truncated at the target commit, working set gone entirely.
        assertEquals(List.of("CREATE TABLE orders (id INT NOT NULL);"),
                metadataStore.commitHistory("feature/orders").stream().map(ChangeSet::ddl).toList());
        assertTrue(metadataStore.workingSet("feature/orders").isEmpty());

        // The branch database was dropped, recreated and replayed into - not patched.
        List<String> againstBranch = connectorFactory.executed.stream()
                .filter(pair -> pair[0].equals("feature_orders_postgres")).map(pair -> pair[1]).toList();
        assertTrue(againstBranch.contains("CREATE TABLE orders (id INT NOT NULL);"), againstBranch.toString());
        List<String> againstAdmin = connectorFactory.executed.stream()
                .filter(pair -> pair[0].equals("postgres")).map(pair -> pair[1]).toList();
        assertTrue(againstAdmin.contains("DROP DATABASE IF EXISTS \"feature_orders_postgres\""), againstAdmin.toString());
        assertTrue(againstAdmin.contains("CREATE DATABASE \"feature_orders_postgres\""), againstAdmin.toString());
    }

    @Test
    void resetLeavesTheOtherBranchesSharingThoseCommitsAlone() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"), new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(runner, new MultiRecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit -m first table");
        dbgit.execute("dbgit checkout -b feature/orders");
        dbgit.add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        dbgit.execute("dbgit commit -m add total");

        dbgit.execute("dbgit reset 1");

        assertEquals(1, metadataStore.commitHistory("feature/orders").size());
        assertEquals(1, metadataStore.commitHistory("main").size());
    }

    @Test
    void resetRefusesMainBecauseItTracksARealDatabase() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> dbgit.execute("dbgit reset 1"));

        assertTrue(exception.getMessage().contains("Cannot reset branch 'main'"), exception.getMessage());
    }

    @Test
    void resetRefusesACommitThatIsNotInTheBranchsHistory() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(runner, new MultiRecordingConnectorFactory(), metadataStore));
        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit.execute("dbgit commit -m first table");
        dbgit.execute("dbgit checkout -b feature/orders");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit reset 99"));

        assertTrue(exception.getMessage().contains("not in branch 'feature/orders' history"), exception.getMessage());
        assertEquals(1, metadataStore.commitHistory("feature/orders").size());
    }

    @Test
    void resetRefusesSomethingThatIsNotACommitId() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit reset head~1"));

        assertTrue(exception.getMessage().contains("Not a commit id"), exception.getMessage());
    }

    /** Commit timestamps are wall-clock, so assertions compare everything about an entry except its Date line. */
    private static List<String> withoutDates(List<String> lines) {
        return lines.stream().filter(line -> !line.startsWith("Date:") && !line.isBlank()).toList();
    }

    /**
     * The failure this guards against is the worst one in the system, and it is silent: two commits both parent
     * off the same HEAD, the second overwrites it, and the first becomes reachable from nothing - while its
     * changesets are already marked COMMIT, so neither the working set nor a reset can ever find them again.
     */
    @Test
    void concurrentCommittersOnOneBranchDoNotLoseEachOthersCommits() throws Exception {
        int committers = 4;
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession owner = listener(new Forker(new RecordingRunner(new CommandResult(0, "true")),
                new MultiRecordingConnectorFactory(), metadataStore));
        owner.execute("dbgit checkout -b feature/orders");
        owner.add("CREATE TABLE orders (id INT NOT NULL);");
        owner.execute("dbgit commit -m base table");

        ExecutorService clients = Executors.newFixedThreadPool(committers);
        try {
            List<Future<?>> pending = new ArrayList<>();
            for (int client = 0; client < committers; client++) {
                DaemonSession session = owner.anotherUser("user" + client);
                String column = "col" + client;
                pending.add(clients.submit(() -> {
                    session.add("ALTER TABLE orders ADD COLUMN " + column + " INT;");
                    return session.execute("dbgit commit -m add " + column);
                }));
            }
            for (Future<?> outcome : pending) {
                outcome.get(20, TimeUnit.SECONDS);
            }
        } finally {
            clients.shutdownNow();
        }

        // Every changeset ever staged on the branch is still reachable by walking its commits.
        List<Long> staged = metadataStore.changesetsForBranch("feature/orders").stream().map(ChangeSet::id).sorted().toList();
        List<Long> reachable = metadataStore.commitHistory("feature/orders").stream().map(ChangeSet::id).sorted().toList();
        assertEquals(committers + 1, staged.size());
        assertEquals(staged, reachable, "a commit was lost: its changesets are no longer reachable from HEAD");
        assertTrue(metadataStore.workingSet("feature/orders").isEmpty());
    }

    /**
     * The row is written before the statement runs. Left behind it would sit at PENDING forever: excluded from
     * appliedChangesets so it could never be committed, yet counted in the working set and destroyed by the next
     * reset - a record of something that never happened.
     */
    @Test
    void aChangesetWhoseDdlFailsAgainstTheDatabaseIsNotLeftBehind() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(),
                new FailingConnectorFactory("CREATE TABLE orders (id INT NOT NULL);"), metadataStore));

        assertThrows(RuntimeException.class, () -> dbgit.add("CREATE TABLE orders (id INT NOT NULL);"));

        assertTrue(metadataStore.changesetsForBranch("main").isEmpty(),
                "a changeset that never reached the database should not survive");
        assertTrue(metadataStore.workingSet("main").isEmpty());
    }

    /**
     * The name is claimed before the database is built. Leaving it claimed would wedge it permanently: the branch
     * would list, its database would not exist, and forking it again would be refused as already existing.
     */
    @Test
    void aForkThatFailsPartWayGivesTheBranchNameBack() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(new CommandResult(0, "true")),
                new FailingConnectorFactory("CREATE DATABASE \"feature_orders_postgres\""), metadataStore));

        assertThrows(RuntimeException.class, () -> dbgit.execute("dbgit checkout -b feature/orders"));

        assertFalse(metadataStore.branches().contains("feature/orders"),
                "the half-built branch should not hold its name");
        // And the name is free again, so a retry is possible rather than permanently refused.
        assertTrue(metadataStore.createBranch("feature/orders", "main"));
    }

    /** Fails one specific statement and passes everything else, so a failure can be aimed at one step. */
    private static final class FailingConnectorFactory implements ConnectorFactory {
        private final String failingStatement;

        private FailingConnectorFactory(String failingStatement) {
            this.failingStatement = failingStatement;
        }

        @Override
        public SqlConnector connect(ConnectionSettings settings) {
            return new SqlConnector() {
                @Override
                public SqlExecutionResult execute(String sql) throws java.sql.SQLException {
                    if (sql.equals(failingStatement)) {
                        throw new java.sql.SQLException("syntax error");
                    }
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
        private final Map<String, TrackedDatabaseConfig> trackedByBranch = new HashMap<>();

        @Override
        public TrackedDatabaseConfig track(String branch, ConnectionSettings settings) {
            TrackedDatabaseConfig tracked = TrackedDatabaseConfig.of(branch, settings);
            trackedByBranch.put(branch, tracked);
            return tracked;
        }

        @Override
        public Optional<TrackedDatabaseConfig> trackedDatabase(String branch) {
            return Optional.ofNullable(trackedByBranch.get(branch));
        }

        private final TreeSet<String> branches = new TreeSet<>(Set.of("main"));
        private final Map<Long, ChangeSet> changesetsById = new LinkedHashMap<>();
        private final Map<Long, List<Long>> changesetIdsByCommit = new LinkedHashMap<>();
        private final Map<Long, Long> parentCommitById = new HashMap<>();
        private final Map<Long, Long> secondParentCommitById = new HashMap<>();
        private final Map<Long, CommitMetadata> metadataByCommit = new HashMap<>();
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
        public void deleteBranch(String branch) {
            branches.remove(branch);
            headCommitByBranch.remove(branch);
        }

        @Override
        public void discardChangeset(long changesetId) {
            changesetsById.remove(changesetId);
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
        public List<CommitEntry> commits(String branch) {
            List<Long> order = new ArrayList<>();
            collectOrder(headCommitByBranch.get(branch), new LinkedHashSet<>(), order);
            List<CommitEntry> entries = new ArrayList<>();
            for (long commitId : order) {
                List<ChangeSet> changesets = changesetIdsByCommit.getOrDefault(commitId, List.<Long>of()).stream()
                        .map(changesetsById::get).toList();
                Commit commit = new Commit(commitId, metadataByCommit.get(commitId), Instant.now(),
                        new CommitParents(parentCommitById.get(commitId), secondParentCommitById.get(commitId)));
                entries.add(new CommitEntry(commit, changesets));
            }
            return entries;
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
        public long commit(String branch, List<Long> changesetIds, CommitMetadata metadata) {
            List<Long> applied = changesetIds.stream()
                    .filter(id -> changesetsById.get(id).status() == ChangesetStatus.APPLIED)
                    .toList();
            long commitId = nextCommitId.getAndIncrement();
            parentCommitById.put(commitId, headCommitByBranch.get(branch));
            metadataByCommit.put(commitId, metadata);
            headCommitByBranch.put(branch, commitId);
            changesetIdsByCommit.put(commitId, applied);
            for (long id : applied) {
                ChangeSet changeset = changesetsById.get(id);
                changesetsById.put(id, new ChangeSet(changeset.id(), changeset.branch(), changeset.ddl(), ChangesetStatus.COMMIT, changeset.appliedAt()));
            }
            return commitId;
        }

        @Override
        public long createMergeCommit(String branch, String otherBranch, CommitMetadata metadata) {
            long commitId = nextCommitId.getAndIncrement();
            parentCommitById.put(commitId, headCommitByBranch.get(branch));
            secondParentCommitById.put(commitId, headCommitByBranch.get(otherBranch));
            metadataByCommit.put(commitId, metadata);
            headCommitByBranch.put(branch, commitId);
            return commitId;
        }

        @Override
        public int resetTo(String branch, long commitId) {
            List<Long> order = new ArrayList<>();
            collectOrder(headCommitByBranch.get(branch), new LinkedHashSet<>(), order);
            if (!order.contains(commitId)) {
                throw new IllegalArgumentException("Commit #" + commitId + " is not in branch '" + branch + "' history.");
            }
            headCommitByBranch.put(branch, commitId);
            List<Long> dropped = changesetsById.values().stream()
                    .filter(changeset -> changeset.branch().equals(branch) && changeset.status() != ChangesetStatus.COMMIT)
                    .map(ChangeSet::id)
                    .toList();
            dropped.forEach(changesetsById::remove);
            return dropped.size();
        }
    }

    @Test
    void initRecordsWhatMainTracksAndSaysSo() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        DbGitCommandResult result = dbgit.execute(
                "dbgit init --host db.internal --port 6543 --database app_prod --user dbgit --password secret");

        assertTrue(result.lines().getFirst().contains("app_prod@db.internal:6543"), result.lines().toString());
        assertTrue(result.lines().get(1).startsWith("Signature: connection_"), result.lines().toString());
    }

    @Test
    void initIsIdempotentForTheSameDatabase() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));
        String command = "dbgit init --host db.internal --port 6543 --database app_prod --user dbgit";

        String firstSignature = dbgit.execute(command).lines().get(1);
        DbGitCommandResult second = dbgit.execute(command);

        assertTrue(second.lines().getFirst().contains("already tracks"), second.lines().toString());
        assertEquals(firstSignature, second.lines().get(1), "the same database always signs the same");
    }

    @Test
    void initRepointsMainAtADifferentDatabase() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));
        dbgit.execute("dbgit init --host db.internal --port 6543 --database app_prod --user dbgit");

        DbGitCommandResult repointed = dbgit.execute(
                "dbgit init --host db.internal --port 6543 --database app_staging --user dbgit");

        assertTrue(repointed.lines().getFirst().contains("app_staging@db.internal:6543"), repointed.lines().toString());
        assertTrue(repointed.lines().getFirst().contains("was app_prod@db.internal:6543"), repointed.lines().toString());
    }

    /** The password reaches the daemon on the request and is used, but the daemon has nowhere to keep it. */
    @Test
    void initRecordsWhatMainTracksWithoutTheCredentialItWasGiven() {
        InMemoryMetadataStore metadataStore = new InMemoryMetadataStore();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                metadataStore));

        dbgit.execute("dbgit init --host db.internal --database app_prod --user dbgit --password hunter2");

        TrackedDatabaseConfig tracked = metadataStore.trackedDatabase("main").orElseThrow();
        assertTrue(tracked.describe().contains("app_prod@db.internal"), tracked.describe());
        assertFalse(tracked.toString().contains("hunter2"),
                "the shared metadata record has no column for a password");
    }

    @Test
    void mainsDdlGoesToTheTrackedDatabaseRatherThanTheScratchpad() {
        RecordingConnectorFactory connectorFactory = new RecordingConnectorFactory();
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), connectorFactory,
                new InMemoryMetadataStore()));
        dbgit.execute("dbgit init --host db.internal --port 6543 --database app_prod --user dbgit");

        dbgit.add("CREATE TABLE orders (id INT NOT NULL);");

        assertEquals("app_prod", connectorFactory.connectedDatabase);
    }

    @Test
    void initRejectsMissingConnectionDetails() {
        DaemonSession dbgit = listener(new Forker(new RecordingRunner(), new RecordingConnectorFactory(),
                new InMemoryMetadataStore()));

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> dbgit.execute("dbgit init --host db.internal"));

        assertTrue(exception.getMessage().contains("required"), exception.getMessage());
    }
}
