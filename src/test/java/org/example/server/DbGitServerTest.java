package org.example.server;

import org.example.adapters.DatabaseSchema;
import org.example.commands.BranchFork;
import org.example.commands.BranchMetadataStore;
import org.example.commands.ChangeSet;
import org.example.commands.ChangesetStatus;
import org.example.commands.CommandResult;
import org.example.commands.CommandRunner;
import org.example.commands.DbGitService;
import org.example.connectors.PostgresConnectorFactory;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitServerTest {
    @TempDir
    Path workingDirectory;

    private DbGitServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        RecordingRunner runner = new RecordingRunner(new CommandResult(0, "true"));
        DbGitService dbGitService = new DbGitService(workingDirectory, new BranchFork(runner, new NoOpConnectorFactory(), new InMemoryMetadataStore()));
        server = new DbGitServer(dbGitService, 0);
        serverThread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException ignored) {
                // Expected once close() shuts the socket down during teardown.
            }
        });
        serverThread.start();
    }

    @AfterEach
    void stopServer() throws IOException, InterruptedException {
        server.close();
        serverThread.join();
    }

    @Test
    void createsAndChecksOutBranchesOverTheSocket() throws IOException {
        Response createResponse = send("dbgit checkout -b feature/orders");
        assertEquals("OK", createResponse.status);
        assertEquals(List.of("Switched to a new branch 'feature/orders'."), createResponse.body);
        assertEquals("feature/orders", Files.readString(workingDirectory.resolve(".dbgit/HEAD")).trim());

        Response checkoutResponse = send("dbgit checkout main");
        assertEquals("OK", checkoutResponse.status);
        assertEquals(List.of("Switched to branch 'main'."), checkoutResponse.body);

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
        Response response = sendAdd("CREATE TABLE orders (\n  id INT PRIMARY KEY\n);");

        assertEquals("OK", response.status);
        assertEquals(List.of("Applied changeset #1 for branch 'main': table 'orders' now has 1 column(s)."), response.body);
    }

    private Response send(String commandLine) throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(commandLine);
                socket.shutdownOutput();

                return readResponse(socket);
            }
        }
    }

    private Response sendAdd(String ddl) throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println("dbgit add");
                writer.print(ddl);
                writer.flush();
                socket.shutdownOutput();

                return readResponse(socket);
            }
        }
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
