package org.example.service;

import org.example.branch.BranchFork;
import org.example.branch.DbGitRepository;
import org.example.replay.SchemaReplayer;
import org.example.service.command.AddCommand;
import org.example.service.command.BranchCommand;
import org.example.service.command.CheckoutCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommitCommand;
import org.example.service.command.CreateBranchCommand;
import org.example.service.command.DiffCommand;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns a raw {@code dbgit} command line into the matching {@link Command} and runs it. Command dispatch (parsing
 * a command line's shape) lives here; each command's own behavior lives in its {@link Command} subclass under
 * {@code org.example.service.command}.
 */
public final class DbGitService {
    private final CommandContext context;

    public DbGitService(Path workingDirectory) {
        this(workingDirectory, new BranchFork());
    }

    public DbGitService(Path workingDirectory, BranchFork branchFork) {
        DbGitRepository repository = new DbGitRepository(Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.context = new CommandContext(repository, Objects.requireNonNull(branchFork, "branchFork must not be null"), new SchemaReplayer());
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        return run(parse(arguments));
    }

    public DbGitCommandResult add(String ddl) {
        return run(new AddCommand(context, ddl));
    }

    private static DbGitCommandResult run(Command command) {
        try {
            return command.execute();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update local .dbgit state", exception);
        }
    }

    private Command parse(List<String> arguments) {
        if (arguments.equals(List.of("dbgit", "branch"))) {
            return new BranchCommand(context);
        }
        if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")
                && arguments.get(2).equals("-b")) {
            return new CreateBranchCommand(context, arguments.get(3));
        }
        if (arguments.size() == 3 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")) {
            return new CheckoutCommand(context, arguments.get(2));
        }
        if (arguments.equals(List.of("dbgit", "commit"))) {
            return new CommitCommand(context);
        }
        if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("diff")) {
            return new DiffCommand(context, arguments.get(2), arguments.get(3));
        }
        throw new IllegalArgumentException(
                "Usage: dbgit checkout -b <branch> | dbgit checkout <branch> | dbgit branch | dbgit add | dbgit commit "
                        + "| dbgit diff <branch1> <branch2>");
    }
}
