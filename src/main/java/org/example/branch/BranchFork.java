package org.example.branch;

import org.example.schema.StableId;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Creates a branch database in one persistent PostgreSQL Docker container. */
public final class BranchFork {
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final int READY_ATTEMPTS = 20;

    private final CommandRunner commandRunner;
    private final PostgresDockerConfig config;

    public BranchFork() {
        this(new ProcessCommandRunner());
    }

    public BranchFork(CommandRunner commandRunner) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = PostgresDockerConfig.getInstance();
    }

    public BranchForkResult fork(String fromBranch, String currentBranch) {
        validateBranch(fromBranch, "fromBranch");
        validateBranch(currentBranch, "currentBranch");
        String databaseName = databaseName(currentBranch);

        ensureSharedContainer();
        waitUntilReady();
        out("Creating database '" + databaseName + "' for branch '" + currentBranch + "'.");
        runChecked("create branch database", postgresCommand("postgres", "CREATE DATABASE " + databaseName));
        runChecked("initialize branch metadata", postgresCommand(databaseName, metadataSql(fromBranch, currentBranch)));
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
                "--env", "POSTGRES_USER=" + config.user(),
                "--env", "POSTGRES_PASSWORD=" + config.password(),
                "--env", "POSTGRES_DB=" + config.adminDatabase(),
                config.image()
        ));
    }

    private void waitUntilReady() {
        out("Waiting for shared PostgreSQL container '" + config.containerName() + "' to accept connections.");
        List<String> command = List.of("docker", "exec", config.containerName(), "pg_isready", "-U", config.user(), "-d", config.adminDatabase());
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (run(command, "check PostgreSQL readiness", false).succeeded()) {
                out("Shared PostgreSQL container '" + config.containerName() + "' is ready.");
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        throw fail("Shared PostgreSQL container did not become ready after " + READY_ATTEMPTS + " attempts.", null);
    }

    private List<String> postgresCommand(String databaseName, String sql) {
        return List.of("docker", "exec", config.containerName(), "psql", "-U", config.user(), "-d", databaseName,
                "-v", "ON_ERROR_STOP=1", "-c", sql);
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
