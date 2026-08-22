package org.example.core.forker.docker;

import org.example.core.forker.ForkException;
import org.example.config.BranchDatabaseConfig;
import org.example.repository.BranchDatabaseRepository;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Ensures the single, persistent PostgreSQL Docker container that every branch's database is forked into is
 * running and ready to accept connections - starting it via the Docker CLI if it isn't, then polling until it
 * accepts a real connection.
 */
public final class SharedPostgresContainer {
    private static final int CONTAINER_PORT = 5432;
    private static final int READY_ATTEMPTS = 20;

    /** Set once the container has been seen accepting connections; never unset, so the check is paid once. */
    private volatile boolean ready;

    private final CommandRunner commandRunner;
    private final BranchDatabaseConfig config;
    private final BranchDatabaseRepository branchDatabases;

    public SharedPostgresContainer(CommandRunner commandRunner, BranchDatabaseConfig config, BranchDatabaseRepository branchDatabases) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.branchDatabases = Objects.requireNonNull(branchDatabases, "branchDatabases must not be null");
    }

    /**
     * Starts the shared container if it isn't already running, then blocks until it accepts connections.
     *
     * <p>Synchronized and remembered. Two threads racing here would both see "not running" and both issue
     * {@code docker run} with the same {@code --name}, and the loser would fail with a name conflict - aborting an
     * otherwise healthy fork. Remembering also means the callers that ask twice for one command, or once per
     * command thereafter, cost a volatile read rather than a subprocess.
     *
     * <p>The flag is set only on success, so a failed start is retried by the next caller.
     */
    public void ensureRunning() {
        if (ready) {
            return;
        }
        synchronized (this) {
            if (ready) {
                return;
            }
            ensureStarted();
            waitUntilReady();
            ready = true;
        }
    }

    private void ensureStarted() {
        CommandResult inspect = run(List.of("docker", "inspect", "--format", "{{.State.Running}}", config.containerName()),
                "inspect shared PostgreSQL container", false);
        if (inspect.succeeded() && inspect.output().equals("true")) {
            out("Reusing shared PostgreSQL container '" + config.containerName() + "'.");
            return;
        }

        out("Starting shared PostgreSQL container '" + config.containerName() + "'.");
        // Another dbgit process may have won the race to create it; that is the outcome we wanted either way.
        CommandResult started = run(List.of(
                "docker", "run", "--detach", "--name", config.containerName(),
                "--publish", config.hostPort() + ":" + CONTAINER_PORT,
                "--env", "POSTGRES_USER=" + config.user(),
                "--env", "POSTGRES_PASSWORD=" + config.password(),
                "--env", "POSTGRES_DB=" + config.adminDatabase(),
                config.image()), "start shared PostgreSQL container", false);
        if (!started.succeeded() && !started.output().contains("already in use")) {
            throw fail("Could not start shared PostgreSQL container. Docker said: " + started.output(), null);
        }
    }

    private void waitUntilReady() {
        out("Waiting for shared PostgreSQL container '" + config.containerName() + "' to accept connections on port " + config.hostPort() + ".");
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (branchDatabases.isReachable()) {
                out("Shared PostgreSQL container '" + config.containerName() + "' is ready.");
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        throw fail("Shared PostgreSQL container did not become ready after " + READY_ATTEMPTS + " attempts.", null);
    }

    private CommandResult run(List<String> command, String operation, boolean printFailure) {
        try {
            CommandResult result = commandRunner.run(command);
            if (printFailure && !result.succeeded()) {
                err("Failed to " + operation + " (exit " + result.exitCode() + "): " + result.output());
            }
            return result;
        } catch (IOException exception) {
            throw fail("Could not " + operation + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw fail("Interrupted while attempting to " + operation + ".", exception);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw fail("Interrupted while waiting for PostgreSQL to start.", exception);
        }
    }

    private static ForkException fail(String message, Throwable cause) {
        err(message);
        return cause == null ? new ForkException(message) : new ForkException(message, cause);
    }

    private static void out(String message) {
        System.out.println("[SharedPostgresContainer] " + message);
        System.out.flush();
    }

    private static void err(String message) {
        System.err.println("[SharedPostgresContainer] " + message);
        System.err.flush();
    }
}
