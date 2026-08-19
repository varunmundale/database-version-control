package org.example.dbgit;

import org.example.branch.BranchFork;
import org.example.branch.CommandResult;
import org.example.branch.CommandRunner;
import org.example.branch.PostgresConnectorFactory;
import org.example.database.SqlConnector;
import org.example.database.SqlExecutionResult;
import org.example.schema.DatabaseSchema;
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
import java.util.ArrayList;
import java.util.List;

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
        DbGitService dbGitService = new DbGitService(workingDirectory, new BranchFork(runner, new NoOpConnectorFactory()));
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
        assertEquals(List.of("* main", "  feature/orders"), branchResponse.body);
    }

    @Test
    void reportsUsageErrorsAsErrStatus() throws IOException {
        Response response = send("dbgit checkout unknown-branch");

        assertEquals("ERR", response.status);
        assertTrue(response.body.get(0).contains("Unknown branch"));
    }

    private Response send(String commandLine) throws IOException {
        try (Socket socket = new Socket("localhost", server.port())) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(commandLine);
                socket.shutdownOutput();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                List<String> body = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    body.add(line);
                }
                return new Response(status, body);
            }
        }
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
                    throw new UnsupportedOperationException();
                }

                @Override
                public void close() {
                }
            };
        }
    }
}
