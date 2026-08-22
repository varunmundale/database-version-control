package org.example.service.command;

import java.util.List;
import java.util.Objects;

/**
 * Turns a raw {@code dbgit} command line's arguments into the matching {@link Command}. Owns command dispatch (the
 * shape each subcommand's argument list takes); each command's own behavior lives in its {@link Command} subclass.
 */
public final class CommandFactory {
    private final CommandContext context;

    public CommandFactory(CommandContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    public Command create(List<String> arguments) {
        if (arguments.size() >= 2 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("init")) {
            return new InitCommand(context, arguments.subList(2, arguments.size()));
        }
        if (arguments.equals(List.of("dbgit", "branch"))) {
            return new BranchCommand(context);
        }
        if (arguments.size() >= 2 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")) {
            return new CheckoutCommand(context, arguments.subList(2, arguments.size()));
        }
        if (arguments.size() >= 2 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("commit")) {
            return new CommitCommand(context, arguments.subList(2, arguments.size()));
        }
        if (arguments.equals(List.of("dbgit", "log"))) {
            return new LogCommand(context);
        }
        if (arguments.size() == 3 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("reset")) {
            return new ResetCommand(context, arguments.get(2));
        }
        if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("diff")) {
            return new DiffCommand(context, arguments.get(2), arguments.get(3));
        }
        if (arguments.size() == 3 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("merge")) {
            return new MergeCommand(context, arguments.get(2));
        }
        throw new IllegalArgumentException(
                "Usage: dbgit init --host <h> [--port 5432] --database <d> --user <u> [--password <w>] "
                        + "| dbgit checkout -b <branch> | dbgit checkout <branch> | dbgit branch | dbgit add "
                        + "| dbgit commit [-m <message>] [--author <name>] | dbgit log | dbgit reset <commit> "
                        + "| dbgit diff <branch1> <branch2> | dbgit merge <branch>");
    }
}
