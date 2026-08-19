package org.example.branch;

import org.example.database.PostgresConnector;
import org.example.database.SqlConnector;
import org.example.schema.StableId;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Creates a branch database in one persistent PostgreSQL Docker container. */
public final class BranchFork {
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final int CONTAINER_PORT = 5432;
    private static final int READY_ATTEMPTS = 20;

    private final CommandRunner commandRunner;
    private final PostgresDockerConfig config;
    private final PostgresConnectorFactory connectorFactory;

    public BranchFork() {
        this(new ProcessCommandRunner());
    }

    public BranchFork(CommandRunner commandRunner) {
        this(commandRunner, defaultConnectorFactory(PostgresDockerConfig.getInstance()));
    }

    public BranchFork(CommandRunner commandRunner, PostgresConnectorFactory connectorFactory) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = PostgresDockerConfig.getInstance();
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
    }

    public BranchForkResult fork(String fromBranch, String currentBranch) {
        validateBranch(fromBranch, "fromBranch");
        validateBranch(currentBranch, "currentBranch");
        String databaseName = databaseName(currentBranch);

        ensureSharedContainer();
        waitUntilReady();
        out("Creating database '" + databaseName + "' for branch '" + currentBranch + "'.");
        executeSql("create branch database", config.adminDatabase(), "CREATE DATABASE \"" + databaseName + "\"");
        executeSql("initialize branch metadata", databaseName, metadataSql(fromBranch, currentBranch));
        out("Recorded fork from '" + fromBranch + "' to '" + currentBranch + "' in database '" + databaseName + "'.");
        out("Branch fork completed for '" + currentBranch + "'.");
        return new BranchForkResult(fromBranch, currentBranch, config.containerName(), databaseName);
    }

    private void ensureSharedContainer() {
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
        try (SqlConnector connector = connectorFactory.connect(config.adminDatabase())) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private void executeSql(String operation, String database, String sql) {
        try (SqlConnector connector = connectorFactory.connect(database)) {
            connector.execute(sql);
        } catch (SQLException exception) {
            throw fail("Could not " + operation + ": " + exception.getMessage(), exception);
        }
    }

    private static PostgresConnectorFactory defaultConnectorFactory(PostgresDockerConfig config) {
        return database -> new PostgresConnector(jdbcUrl(config, database));
    }

    private static String jdbcUrl(PostgresDockerConfig config, String database) {
        return "jdbc:postgresql://localhost:" + config.hostPort() + "/" + database
                + "?user=" + config.user() + "&password=" + config.password();
    }

    private static String metadataSql(String fromBranch, String currentBranch) {
        return "CREATE TABLE IF NOT EXISTS branch_metadata ("
                + "branch_name TEXT PRIMARY KEY, forked_from TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                + "INSERT INTO branch_metadata (branch_name, forked_from) VALUES ('" + currentBranch + "', '" + fromBranch + "') "
                + "ON CONFLICT (branch_name) DO UPDATE SET forked_from = EXCLUDED.forked_from;";
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

    private static void validateBranch(String branch, String argumentName) {
        if (branch == null || !BRANCH_NAME.matcher(branch).matches()) {
            throw new IllegalArgumentException(argumentName + " must be a non-empty Git-style branch name");
        }
    }

    private static String databaseName(String currentBranch) {
        String readableBranch = currentBranch.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        String readablePrefix = readableBranch.length() > 40 ? readableBranch.substring(0, 40) : readableBranch;
        String suffix = StableId.of("branch", currentBranch).value().substring("branch_".length(), "branch_".length() + 8);
        return "branch_" + readablePrefix + "_" + suffix;
    }

    private static BranchForkException fail(String message, Throwable cause) {
        err(message);
        return cause == null ? new BranchForkException(message) : new BranchForkException(message, cause);
    }

    private static void out(String message) {
        System.out.println("[BranchFork] " + message);
        System.out.flush();
    }

    private static void err(String message) {
        System.err.println("[BranchFork] " + message);
        System.err.flush();
    }
}
