package org.example.unit.service;


import org.example.service.DbGitCommandListener;
import org.example.core.forker.Forker;
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
import org.example.core.versioning.VersioningService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.example.config.ConcurrencyConfig;
import org.example.unit.core.locking.TestBranchLocks;
import org.example.protocol.RequestContext;
import org.example.protocol.RequestHeader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitCommandListenerTest {
    @TempDir
    Path workingDirectory;

    private DbGitCommandListener listener;
    private Thread serverThread;

    /** What a client would keep in its own .dbgit workspace, and send with every request. */
    private final ConnectionSettings tracked = new ConnectionSettings("localhost", 5432, "tester", "", "app");
    private String branch = RequestContext.DEFAULT_BRANCH;

    @BeforeEach
    void startServer() throws IOException {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        listener = new DbGitCommandListener(
                new Forker(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()), 0,
                ConcurrencyConfig.getInstance(), TestBranchLocks.inMemory());
        // main tracks a real database now, so point it at one before any request arrives.
        listener.execute(new RequestContext("tester", branch, tracked), "dbgit init");
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
    void createsAndChecksOutBranchesOverTheSocket() throws IOException {
        Response createResponse = send("dbgit checkout -b feature/orders");
        assertEquals("OK", createResponse.status);
        assertEquals(List.of("Switched to a new branch 'feature/orders'."), createResponse.body);

        // The daemon keeps no HEAD; the client moves its own once the daemon has accepted the checkout.
        branch = "feature/orders";
        assertEquals(List.of("* feature/orders", "  main"), send("dbgit branch").body);

        Response checkoutResponse = send("dbgit checkout main");
        assertEquals("OK", checkoutResponse.status);
        assertEquals(List.of("Switched to branch 'main'."), checkoutResponse.body);

        branch = RequestContext.DEFAULT_BRANCH;
        Response branchResponse = send("dbgit branch");
        assertEquals("OK", branchResponse.status);
        assertEquals(List.of("  feature/orders", "* main"), branchResponse.body);
    }

    @Test
    void reportsUsageErrorsAsErrStatus() throws IOException {
        Response response = send("dbgit checkout unknown-branch");

        assertEquals("ERR", response.status);
        assertTrue(response.body.get(0).contains("Unknown branch"));
    }

    @Test
    void appliesAMultilineDdlStatementAndRecordsAChangesetOverTheSocket() throws IOException {
        Response response = sendAdd("CREATE TABLE orders (\n  id INT NOT NULL\n);");

        assertEquals("OK", response.status);
        assertEquals(List.of("Applied changeset #1 for branch 'main': table 'orders' now has 1 column(s)."), response.body);
    }

    private Response send(String commandLine) throws IOException {
        try (Socket socket = new Socket("localhost", listener.port())) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(header());
                writer.println(commandLine);
                socket.shutdownOutput();

                return readResponse(socket);
            }
        }
    }

    private Response sendAdd(String ddl) throws IOException {
        try (Socket socket = new Socket("localhost", listener.port())) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(header());
                writer.println("dbgit add");
                writer.print(ddl);
                writer.flush();
                socket.shutdownOutput();

                return readResponse(socket);
            }
        }
    }

    /**
     * If the daemon were still serial the barrier would never trip and this would time out - which is the point:
     * it proves the handlers really do overlap, rather than merely that three requests eventually succeeded.
     */
    @Test
    void concurrentClientsAreHandledInParallel() throws Exception {
        int clients = 3;
        CyclicBarrier allInside = new CyclicBarrier(clients);
        try (Daemon daemon = daemon(new ConcurrencyConfig(clients, 8, 5000, 5000, 60000), () -> {
            try {
                allInside.await(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                throw new IllegalStateException("handlers did not overlap", exception);
            }
        })) {
            List<Response> responses = daemon.inParallel(clients, client -> "feature/" + client);

            assertEquals(clients, responses.stream().filter(response -> response.status.equals("OK")).count(),
                    responses.toString());
        }
    }

    /**
     * One handler, a queue of one: the third and fourth callers cannot be served at all. They are told so, rather
     * than being dropped or left queued behind work that has not started.
     */
    @Test
    void whenEveryHandlerAndTheQueueAreFullTheRestAreToldTheServerIsBusy() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (Daemon daemon = daemon(new ConcurrencyConfig(1, 1, 5000, 5000, 60000), () -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        })) {
            List<Response> responses = daemon.inParallel(4, client -> RequestContext.DEFAULT_BRANCH,
                    release::countDown);

            long busy = responses.stream()
                    .filter(response -> "ERR".equals(response.status)
                            && response.body.getFirst().contains("Server busy"))
                    .count();
            assertEquals(2, busy, "one handler plus a queue of one leaves two callers unserved: " + responses);
            assertTrue(responses.stream().allMatch(response -> response.status != null),
                    "no connection should be answered with nothing at all");
        }
    }

    /**
     * The other half of the same guarantee: work on one branch is serialized, however many handlers are free. An
     * unserialized daemon would have all three inside at once, since nothing else here blocks.
     */
    @Test
    void addsToOneBranchAreSerializedEvenWhenHandlersAreFree() throws Exception {
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger mostAtOnce = new AtomicInteger();
        try (Daemon daemon = daemon(new ConcurrencyConfig(4, 8, 5000, 5000, 60000), () -> {
            mostAtOnce.accumulateAndGet(inside.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            inside.decrementAndGet();
        })) {
            List<Response> responses = daemon.inParallel(3, client -> "feature/shared");

            assertEquals(3, responses.stream().filter(response -> response.status.equals("OK")).count(),
                    responses.toString());
            assertEquals(1, mostAtOnce.get(), "two commands were inside the same branch at once");
            // Each add validated against its predecessor's effect, so the changesets are numbered in the order
            // they actually ran - which is what a later replay depends on.
            assertEquals(List.of(1L, 2L, 3L), responses.stream().map(DbGitCommandListenerTest::changesetId).sorted().toList());
        }
    }

    private static long changesetId(Response response) {
        String line = response.body.getFirst();
        return Long.parseLong(line.substring(line.indexOf('#') + 1, line.indexOf(" for ")));
    }

    /** A daemon of its own, so a test can choose its thread count without disturbing the shared one. */
    private Daemon daemon(ConcurrencyConfig concurrency, Runnable gate) throws IOException {
        ConnectorFactory connectors = settings -> new SqlConnector() {
            @Override
            public SqlExecutionResult execute(String sql) {
                gate.run();
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
        DbGitCommandListener owned = new DbGitCommandListener(
                new Forker(new RecordingRunner(), connectors, new InMemoryMetadataStore()), 0, concurrency,
                TestBranchLocks.inMemory());
        // Reaches the database only to connect, never to execute, so the gate does not hold up start-up.
        owned.execute(new RequestContext("tester", RequestContext.DEFAULT_BRANCH, tracked), "dbgit init");
        Thread thread = new Thread(() -> {
            try {
                owned.serve();
            } catch (IOException ignored) {
                // Expected once close() shuts the socket down.
            }
        });
        thread.start();
        return new Daemon(owned, thread);
    }

    private final class Daemon implements AutoCloseable {
        private final DbGitCommandListener owned;
        private final Thread thread;

        private Daemon(DbGitCommandListener owned, Thread thread) {
            this.owned = owned;
            this.thread = thread;
        }

        /** Fires {@code clients} concurrent {@code dbgit add}s, running {@code afterSubmitting} once all are in flight. */
        List<Response> inParallel(int clients, IntFunction<String> branchOf, Runnable... afterSubmitting)
                throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(clients);
            try {
                List<Future<Response>> pending = new ArrayList<>();
                for (int client = 0; client < clients; client++) {
                    String branch = branchOf.apply(client);
                    String ddl = "CREATE TABLE orders" + client + " (id INT NOT NULL);";
                    pending.add(pool.submit(() -> sendTo(owned.port(), branch, ddl)));
                    Thread.sleep(120);
                }
                for (Runnable release : afterSubmitting) {
                    release.run();
                }
                List<Response> responses = new ArrayList<>();
                for (Future<Response> response : pending) {
                    responses.add(response.get(15, TimeUnit.SECONDS));
                }
                return responses;
            } finally {
                pool.shutdownNow();
            }
        }

        @Override
        public void close() throws Exception {
            owned.close();
            thread.join();
        }
    }

    private Response sendTo(int port, String onBranch, String ddl) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(RequestHeader.render(new RequestContext("tester", onBranch, tracked)));
                writer.println("dbgit add");
                writer.print(ddl);
                writer.flush();
                socket.shutdownOutput();
                return readResponse(socket);
            }
        }
    }

    private String header() {
        return RequestHeader.render(new RequestContext("tester", branch, tracked));
    }

    private static Response readResponse(Socket socket) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        String status = reader.readLine();
        List<String> body = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            body.add(line);
        }
        return new Response(status, body);
    }

    private record Response(String status, List<String> body) {
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
                entries.add(new CommitEntry(new Commit(commitId, branch, new CommitMetadata("tester", ""), Instant.now(),
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
