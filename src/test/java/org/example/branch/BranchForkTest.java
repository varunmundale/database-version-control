package org.example.branch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchForkTest {
    @Test
    void startsSharedPostgresCreatesABranchDatabaseAndPrintsStatus() {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(1, "No such container"), new CommandResult(0, "container-id"),
                new CommandResult(0, "accepting connections"), new CommandResult(0, "CREATE DATABASE"),
                new CommandResult(0, "INSERT 0 1")
        );
        BranchFork branchFork = new BranchFork(runner);

        BranchForkResult result = branchFork.fork("main", "feature/orders");

        assertEquals("main", result.fromBranch());
        assertEquals("feature/orders", result.currentBranch());
            assertEquals("postgres-branches-scratchpad", result.containerName());
        assertTrue(result.databaseName().startsWith("branch_feature-orders_"));
        assertEquals(5, runner.commands.size());
            assertEquals(List.of("docker", "inspect", "--format", "{{.State.Running}}", "postgres-branches-scratchpad"), runner.commands.getFirst());
            assertEquals(List.of("docker", "run", "--detach", "--name", "postgres-branches-scratchpad"), runner.commands.get(1).subList(0, 5));
            assertEquals(List.of("docker", "exec", "postgres-branches-scratchpad", "pg_isready", "-U", "postgres", "-d", "postgres"), runner.commands.get(2));
        assertEquals("CREATE DATABASE " + result.databaseName(), runner.commands.get(3).getLast());
        assertTrue(runner.commands.get(4).getLast().contains("VALUES ('feature/orders', 'main')"));
    }

    @Test
    void surfacesDockerFailures() {
        RecordingRunner runner = new RecordingRunner(new CommandResult(1, "No such container"), new CommandResult(125, "docker daemon is unavailable"));
        BranchFork branchFork = new BranchFork(runner);

        BranchForkException exception = assertThrows(BranchForkException.class, () -> branchFork.fork("main", "feature/orders"));

        assertTrue(exception.getMessage().contains("start shared PostgreSQL container"));
    }

    @Test
    void reusesTheRunningSharedContainerForAnotherBranch() {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(0, "true"), new CommandResult(0, "accepting connections"),
                new CommandResult(0, "CREATE DATABASE"), new CommandResult(0, "INSERT 0 1")
        );
        BranchFork branchFork = new BranchFork(runner);

        BranchForkResult result = branchFork.fork("main", "feature/payments");

        assertEquals("postgres-branches-scratchpad", result.containerName());
        assertEquals(4, runner.commands.size());
        assertTrue(runner.commands.stream().noneMatch(command -> command.contains("run")));
        assertEquals("CREATE DATABASE " + result.databaseName(), runner.commands.get(2).getLast());
    }

    private static final class RecordingRunner implements CommandRunner {
        private final List<CommandResult> results;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingRunner(CommandResult... results) {
            this.results = new ArrayList<>(List.of(results));
        }

        @Override
        public CommandResult run(List<String> command) throws IOException {
            commands.add(List.copyOf(command));
            return results.removeFirst();
        }
    }
}
