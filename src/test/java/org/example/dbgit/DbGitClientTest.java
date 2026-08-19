package org.example.dbgit;

import org.example.branch.BranchFork;
import org.example.branch.CommandResult;
import org.example.branch.CommandRunner;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DbGitClientTest {
    @TempDir
    Path workingDirectory;

    private DbGitServer server;
    private Thread serverThread;

    @BeforeEach
    void startServer() throws IOException {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(0, "true"), new CommandResult(0, "accepting connections"),
                new CommandResult(0, "CREATE DATABASE"), new CommandResult(0, "INSERT 0 1")
        );
        DbGitService dbGitService = new DbGitService(workingDirectory, new BranchFork(runner));
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
    void printsCommandOutputAndReturnsSuccessExitCode() {
        DbGitClient client = new DbGitClient(server.port());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.run(List.of("checkout", "-b", "feature/orders"), printStream(outBytes), printStream(errBytes));

        assertEquals(0, exitCode);
        assertEquals("Switched to a new branch 'feature/orders'." + System.lineSeparator(), outBytes.toString(StandardCharsets.UTF_8));
        assertEquals("", errBytes.toString(StandardCharsets.UTF_8));
    }

    @Test
    void printsErrorsToStderrAndReturnsFailureExitCode() {
        DbGitClient client = new DbGitClient(server.port());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

        int exitCode = client.run(List.of("checkout", "unknown-branch"), printStream(outBytes), printStream(errBytes));

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

        int exitCode = client.run(List.of("branch"), printStream(outBytes), printStream(errBytes));

        assertEquals(1, exitCode);
        assertTrue(errBytes.toString(StandardCharsets.UTF_8).contains("dbService is not running"));
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
}
