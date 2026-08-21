package org.example.service;

import org.example.branch.BranchFork;
import org.example.branch.DbGitRepository;
import org.example.core.SchemaReplayer;
import org.example.service.command.AddCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns a raw {@code dbgit} command line into the matching {@link Command} (via {@link CommandFactory}) and runs
 * it. Each command's own behavior lives in its {@link Command} subclass under {@code org.example.service.command}.
 */
public final class DbGitService {
    private final CommandContext context;
    private final CommandFactory commandFactory;

    public DbGitService(Path workingDirectory) {
        this(workingDirectory, new BranchFork());
    }

    public DbGitService(Path workingDirectory, BranchFork branchFork) {
        DbGitRepository repository = new DbGitRepository(Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.context = new CommandContext(repository, Objects.requireNonNull(branchFork, "branchFork must not be null"), new SchemaReplayer());
        this.commandFactory = new CommandFactory(context);
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        return run(commandFactory.create(arguments));
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
}
