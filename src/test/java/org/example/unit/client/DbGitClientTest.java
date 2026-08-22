package org.example.unit.client;


import org.example.client.DbGitClient;
import org.example.core.forker.Forker;
import org.example.config.ConcurrencyConfig;
import org.example.core.locking.BranchLocks;
import org.example.core.forker.docker.CommandResult;
import org.example.core.forker.docker.CommandRunner;
import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.connectors.SqlTransaction;
import org.example.config.TrackedDatabaseConfig;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitMetadata;
import org.example.models.versioning.CommitParents;
import org.example.models.versioning.CommitEntry;
import org.example.models.versioning.Commit;
import org.example.models.versioning.ChangesetStatus;
import org.example.service.DbGitCommandListener;
import org.example.protocol.RequestContext;
import org.example.core.versioning.VersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitClientTest {
    @TempDir
    Path workingDirectory;

    private DbGitCommandListener listener;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        listener = new DbGitCommandListener(
                new Forker(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()), 0,
                ConcurrencyConfig.getInstance(), BranchLocks.inMemory());
        // main tracks a real database now, so point it at one before any request arrives.
        listener.execute(request(), "dbgit init");
        serverThread = new Thread(() -> {
            try {
                listener.serve();
            } catch (IOException ignored) {
                // Expected once close() shuts the socket down during teardown.
            }
        });
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws IOException, InterruptedException {
        listener.close();
        serverThread.join();
    }

    @Test
    void printsCommandOutputAndReturnsSuccessExitCode() {
        DbGitClient client = new DbGitClient(listener.port());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.run(request(), List.of("checkout", "-b", "feature/orders"), printStream(outBytes), printStream(errBytes));

        assertEquals(0, exitCode);
        assertEquals("Switched to a new branch 'feature/orders'." + System.lineSeparator(), outBytes.toString(StandardCharsets.UTF_8));
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void printsErrorsToStderrAndReturnsFailureExitCode() {
        DbGitClient client = new DbGitClient(listener.port());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.run(request(), List.of("checkout", "unknown-branch"), printStream(outBytes), printStream(errBytes));

        assertEquals(1, exitCode);
        assertEquals("", outBytes.toString(StandardCharsets.UTF_8));
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("Unknown branch"));
    }

    @Test
    void reportsWhenDbServiceIsNotRunning() throws IOException {
        int freePort;
        try (ServerSocket probe = new ServerSocket(0)) {
            freePort = probe.getLocalPort();
        }
        DbGitClient client = new DbGitClient(freePort);
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.run(request(), List.of("branch"), printStream(outBytes), printStream(errBytes));

        assertEquals(1, exitCode);
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("dbService is not running"));
    }

    @Test
    void sendsAMultilineDdlStatementAndReportsTheRecordedChangeset() {
        DbGitClient client = new DbGitClient(listener.port());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.runAdd(request(), "CREATE TABLE orders (\n  id INT NOT NULL\n);", printStream(outBytes), printStream(errBytes));

        assertEquals(0, exitCode);
        assertEquals("Applied changeset #1 for branch 'main': table 'orders' now has 1 column(s)." + System.lineSeparator(),
                outBytes.toString(StandardCharsets.UTF_8));
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
    }

    /** What a client workspace would send: this caller, on main, with the connection it has configured. */
    private static RequestContext request() {
        return new RequestContext("tester", RequestContext.DEFAULT_BRANCH,
                new ConnectionSettings("localhost", 5432, "tester", "", "app"));
    }

    private static PrintStream printStream(ByteArrayOutputStream bytes) {
        return new PrintStream(bytes, true, StandardCharsets.UTF_8);
    }

    private static final class RecordingRunner implements CommandRunner {
        private final List<CommandResult> results;

        private RecordingRunner(CommandResult... results) {
            this.results = new ArrayList<>(List.of(results));
        }

        @Override
        public CommandResult run(List<String> command) {
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
            List<Long> chain = new ArrayList<>();
            Long current = headCommitByBranch.get(branch);
            while (current != null) {
                chain.add(current);
                current = parentCommitById.get(current);
            }
            Collections.reverse(chain);
            List<CommitEntry> entries = new ArrayList<>();
            for (long commitId : chain) {
                List<ChangeSet> changesets = changesetIdsByCommit.getOrDefault(commitId, List.<Long>of()).stream()
                        .map(changesetsById::get).toList();
                entries.add(new CommitEntry(new Commit(commitId, new CommitMetadata("tester", ""), Instant.now(),
                        new CommitParents(parentCommitById.get(commitId), null)), changesets));
            }
            return entries;
        }

        @Override
        public long commit(String branch, List<Long> changesetIds, CommitMetadata metadata) {
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
        public long createMergeCommit(String branch, String otherBranch, CommitMetadata metadata) {
            long commitId = nextCommitId.getAndIncrement();
            parentCommitById.put(commitId, headCommitByBranch.get(branch));
            headCommitByBranch.put(branch, commitId);
            return commitId;
        }

        @Override
        public int resetTo(String branch, long commitId) {
            headCommitByBranch.put(branch, commitId);
            return 0;
        }
    }
}
