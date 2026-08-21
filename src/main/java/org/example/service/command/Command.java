package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/**
 * One {@code dbgit} subcommand - constructed with the shared {@link CommandContext} plus whatever arguments that
 * specific command needs (a branch name, a DDL statement, ...), then run via {@link #execute()}.
 * {@link CommandFactory} owns turning a raw command line into the right concrete {@link Command}; each command
 * owns only its own behavior.
 */
public abstract class Command {
    protected final CommandContext context;

    protected Command(CommandContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    public abstract DbGitCommandResult execute();

    protected static DbGitCommandResult print(List<String> lines) {
        lines.forEach(System.out::println);
        return new DbGitCommandResult(lines);
    }
}
