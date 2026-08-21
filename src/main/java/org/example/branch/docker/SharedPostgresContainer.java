package org.example.branch.docker;

import org.example.branch.BranchForkException;
import org.example.config.BranchDatabaseConfig;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.SqlConnector;

import java.io.IOException;
import java.sql.SQLException;
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

    private final CommandRunner commandRunner;
    private final BranchDatabaseConfig config;
    private final ConnectorFactory connectorFactory;

    public SharedPostgresContainer(CommandRunner commandRunner, BranchDatabaseConfig config, ConnectorFactory connectorFactory) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
    }

    /** Starts the shared container if it isn't already running, then blocks until it accepts connections. */
    public void ensureRunning() {
        ensureStarted();
        waitUntilReady();
    }

    private void ensureStarted() {
        CommandResult inspect = run(List.of("docker", "inspect", "--format", "{{.State.Running}}", config.containerName()),
                "inspect shared PostgreSQL container", false);
        if (inspect.succeeded() && inspect.output().equals("true")) {
            out("Reusing shared PostgreSQL container '" + config.containerName() + "'.");
            return;
        }

        out("Starting shared PostgreSQL container '" + config.containerName() + "'.");
        runChecked("start shared PostgreSQL container", List.of(
                "docker", "run", "--detach", "--name", config.containerName(),
                "--publish", config.hostPort() + ":" + CONTAINER_PORT,
                "--env", "POSTGRES_USER=" + config.user(),
                "--env", "POSTGRES_PASSWORD=" + config.password(),
                "--env", "POSTGRES_DB=" + config.adminDatabase(),
                config.image()
        ));
    }

    private void waitUntilReady() {
        out("Waiting for shared PostgreSQL container '" + config.containerName() + "' to accept connections on port " + config.hostPort() + ".");
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (canConnect()) {
                out("Shared PostgreSQL container '" + config.containerName() + "' is ready.");
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        throw fail("Shared PostgreSQL container did not become ready after " + READY_ATTEMPTS + " attempts.", null);
    }

    private boolean canConnect() {
        try (SqlConnector connector = connectorFactory.connect(config.connectionTo(config.adminDatabase()))) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private void runChecked(String operation, List<String> command) {
        CommandResult result = run(command, operation, true);
        if (!result.succeeded()) {
            throw fail("Could not " + operation + ". Docker exited with code " + result.exitCode() + ".", null);
        }
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

    private static BranchForkException fail(String message, Throwable cause) {
        err(message);
        return cause == null ? new BranchForkException(message) : new BranchForkException(message, cause);
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
