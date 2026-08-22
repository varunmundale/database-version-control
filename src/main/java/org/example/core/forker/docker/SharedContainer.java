package org.example.core.forker.docker;

import org.example.core.forker.ForkException;
import org.example.config.BranchDatabaseConfig;
import org.example.repository.BranchDatabaseRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Ensures the single, persistent Docker container every branch's database is forked into is running and ready to
 * accept connections - starting it via the Docker CLI if it isn't, then polling until it accepts a real connection.
 *
 * <p>A no-op for any {@code branchDatabases.dialect} {@link ContainerSpec} has no entry for: an in-memory H2
 * database needs no server, no container and no Docker at all, so starting one would be pure waste. For a dialect
 * that is a real server process - {@code postgresql}, {@code mysql} - the spec supplies the one thing that differs
 * between them (the image's own env vars, its container port); everything else here - the readiness poll, the
 * reuse-if-already-running check - is genuinely the same regardless of which server it is.
 */
public final class SharedContainer {
    private static final Map<String, ContainerSpec> SPECS = ContainerSpec.builtins();
    private static final int READY_ATTEMPTS = 20;

    /** Set once the container has been seen accepting connections; never unset, so the check is paid once. */
    private volatile boolean ready;

    private final CommandRunner commandRunner;
    private final BranchDatabaseConfig config;
    private final BranchDatabaseRepository branchDatabases;
    private final ContainerSpec spec;

    public SharedContainer(CommandRunner commandRunner, BranchDatabaseRepository branchDatabases) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = BranchDatabaseConfig.getInstance();
        this.branchDatabases = Objects.requireNonNull(branchDatabases, "branchDatabases must not be null");
        this.spec = SPECS.get(config.dialect());
    }

    public void ensureRunning() {
        if (spec == null || ready) {
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
                "inspect shared " + spec.label() + " container", false);
        if (inspect.succeeded() && inspect.output().equals("true")) {
            out("Reusing shared " + spec.label() + " container '" + config.containerName() + "'.");
            return;
        }

        out("Starting shared " + spec.label() + " container '" + config.containerName() + "'.");
        // Another dbgit process may have won the race to create it; that is the outcome we wanted either way.
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "--detach", "--name", config.containerName(),
                "--publish", config.hostPort() + ":" + spec.containerPort()));
        spec.envArgs().apply(config).forEach(command::add);
        command.add(config.image());
        CommandResult started = run(command, "start shared " + spec.label() + " container", false);
        if (!started.succeeded() && !started.output().contains("already in use")) {
            throw fail("Could not start shared " + spec.label() + " container. Docker said: " + started.output(), null);
        }
    }

    private void waitUntilReady() {
        out("Waiting for shared " + spec.label() + " container '" + config.containerName()
                + "' to accept connections on port " + config.hostPort() + ".");
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (branchDatabases.isReachable()) {
                out("Shared " + spec.label() + " container '" + config.containerName() + "' is ready.");
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        throw fail("Shared " + spec.label() + " container did not become ready after " + READY_ATTEMPTS + " attempts.", null);
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
            throw fail("Interrupted while waiting for " + spec.label() + " to start.", exception);
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
