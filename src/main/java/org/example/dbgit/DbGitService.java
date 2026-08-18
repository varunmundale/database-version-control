package org.example.dbgit;

import org.example.branch.BranchFork;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** Command service supporting {@code dbgit checkout} and {@code dbgit branch}. */
public final class DbGitService {
    private final DbGitRepository repository;
    private final BranchFork branchFork;

    public DbGitService(Path workingDirectory) {
        this(workingDirectory, new BranchFork());
    }

    public DbGitService(Path workingDirectory, BranchFork branchFork) {
        repository = new DbGitRepository(Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.branchFork = Objects.requireNonNull(branchFork, "branchFork must not be null");
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        try {
            if (arguments.equals(List.of("dbgit", "branch"))) {
                return print(branchLines());
            }
            if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")
                    && arguments.get(2).equals("-b")) {
                return createAndCheckout(arguments.get(3));
            }
            if (arguments.size() == 3 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")) {
                return checkout(arguments.get(2));
            }
            throw new IllegalArgumentException("Usage: dbgit checkout -b <branch> | dbgit checkout <branch> | dbgit branch");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update local .dbgit state", exception);
        }
    }

    private DbGitCommandResult createAndCheckout(String branch) throws IOException {
        String fromBranch = repository.currentBranch();
        if (repository.branchExists(branch)) {
            throw new IllegalArgumentException("Branch already exists: " + branch);
        }
        branchFork.fork(fromBranch, branch);
        repository.createAndCheckout(branch);
        return print(List.of("Switched to a new branch '" + branch + "'."));
    }

    private DbGitCommandResult checkout(String branch) throws IOException {
        repository.checkout(branch);
        return print(List.of("Switched to branch '" + branch + "'."));
    }

    private List<String> branchLines() throws IOException {
        String currentBranch = repository.currentBranch();
        List<String> lines = new ArrayList<>();
        for (String branch : repository.branches()) {
            lines.add((branch.equals(currentBranch) ? "* " : "  ") + branch);
        }
        return lines;
    }

    private static DbGitCommandResult print(List<String> lines) {
        lines.forEach(System.out::println);
        return new DbGitCommandResult(lines);
    }
}
