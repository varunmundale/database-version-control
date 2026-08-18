package org.example.dbgit;

import org.example.branch.BranchFork;
import org.example.branch.CommandResult;
import org.example.branch.CommandRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DbGitServiceTest {
    @TempDir
    Path workingDirectory;

    @Test
    void createsABranchDatabaseAndWritesLocalDbGitState() throws IOException {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(0, "true"), new CommandResult(0, "accepting connections"),
                new CommandResult(0, "CREATE DATABASE"), new CommandResult(0, "INSERT 0 1")
        );
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(runner));

        DbGitCommandResult result = service.execute("dbgit checkout -b feature/orders");

        assertEquals(List.of("Switched to a new branch 'feature/orders'."), result.lines());
        assertEquals("feature/orders", Files.readString(workingDirectory.resolve(".dbgit/HEAD")).trim());
        assertEquals(List.of("main", "feature/orders"), Files.readAllLines(workingDirectory.resolve(".dbgit/branches")));
        assertEquals(4, runner.commands.size());
    }

    @Test
    void checksOutExistingBranchesAndListsAllBranches() {
        RecordingRunner runner = new RecordingRunner(
                new CommandResult(0, "true"), new CommandResult(0, "accepting connections"),
                new CommandResult(0, "CREATE DATABASE"), new CommandResult(0, "INSERT 0 1")
        );
        DbGitService service = new DbGitService(workingDirectory, new BranchFork(runner));
        service.execute("dbgit checkout -b feature/orders");

        DbGitCommandResult checkout = service.execute("dbgit checkout main");
        DbGitCommandResult branchList = service.execute("dbgit branch");

        assertEquals(List.of("Switched to branch 'main'."), checkout.lines());
        assertEquals(List.of("* main", "  feature/orders"), branchList.lines());
        assertEquals(4, runner.commands.size());
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
