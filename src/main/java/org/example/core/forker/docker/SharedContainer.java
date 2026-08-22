package org.example.core.forker.docker;

import org.example.core.forker.ForkException;
import org.example.config.BranchDatabaseConfig;
import org.example.repository.BranchDatabaseRepository;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Ensures the single, persistent Docker container every branch's database is forked into is running and ready to
 * accept connections - starting it via the Docker CLI if it isn't, then polling until it accepts a real connection.
 *
 * <p>A no-op for any {@code branchDatabases.dialect} but {@code postgresql}: an in-memory H2 database needs no
 * server, no container and no Docker at all, so starting one would be pure waste. The docker commands themselves
 * stay Postgres-specific (the image env vars, the container's own port), since {@code postgresql} is the only
 * dialect that is actually a server process to bring up - the name and the dialect check are what generalized, so
 * {@link org.example.core.forker.Forker} does not have to know which dialects need a container and which don't.
 */
public final class SharedContainer {
    private static final String POSTGRESQL_DIALECT = "postgresql";
    private static final int CONTAINER_PORT = 5432;
    private static final int READY_ATTEMPTS = 20;

    /** Set once the container has been seen accepting connections; never unset, so the check is paid once. */
    private volatile boolean ready;

    private final CommandRunner commandRunner;
    private final BranchDatabaseConfig config;
    private final BranchDatabaseRepository branchDatabases;

    public SharedContainer(CommandRunner commandRunner, BranchDatabaseRepository branchDatabases) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = BranchDatabaseConfig.getInstance();
        this.branchDatabases = Objects.requireNonNull(branchDatabases, "branchDatabases must not be null");
    }

    public void ensureRunning() {
        if (!config.dialect().equals(POSTGRESQL_DIALECT) || ready) {
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
        System.out.println("[SharedContainer] " + message);
        System.out.flush();
    }

    private static void err(String message) {
        System.err.println("[SharedContainer] " + message);
        System.err.flush();
    }
}
