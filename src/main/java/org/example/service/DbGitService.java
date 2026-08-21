package org.example.service;

import org.example.core.forker.Forker;
import org.example.core.replayer.Replayer;
import org.example.repository.DbGitLocalRepository;
import org.example.service.command.AddCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Turns a raw {@code dbgit} command line into the matching {@link Command} (via {@link CommandFactory}) and runs
 * it. Each command's own behavior lives in its {@link Command} subclass under {@code org.example.service.command}.
 *
 * <p>Transport is somebody else's problem: {@link DbGitCommandListener} handles sockets and calls in here, which is
 * also what lets commands be driven directly, without a socket.
 */
public final class DbGitService {
    private final CommandContext context;
    private final CommandFactory commandFactory;

    public DbGitService(Path workingDirectory) {
        this(workingDirectory, new Forker());
    }

    public DbGitService(Path workingDirectory, Forker forker) {
        DbGitLocalRepository repository = new DbGitLocalRepository(
                Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.context = new CommandContext(repository, Objects.requireNonNull(forker, "forker must not be null"), new Replayer());
        this.commandFactory = new CommandFactory(context);
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        return commandFactory.create(arguments).execute();
    }

    /** {@code dbgit add} arrives separately because its DDL body comes from stdin rather than the argument list. */
    public DbGitCommandResult add(String ddl) {
        return new AddCommand(context, ddl).execute();
    }
}
