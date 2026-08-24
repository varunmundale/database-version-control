package org.example.service.command;

import org.example.request.RequestContext;

import java.util.List;
import java.util.Objects;

/**
 * Turns a raw {@code dbgit} command line's arguments into the matching {@link Command}, binding the daemon's
 * shared collaborators to the caller's own {@link RequestContext} per call.
 */
public final class CommandFactory {
    private final CommandContext context;

    public CommandFactory(CommandContext context) {
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    public Command create(RequestContext request, List<String> arguments) {
        CommandContext context = this.context.forRequest(Objects.requireNonNull(request, "request must not be null"));
        if (arguments.isEmpty() || !arguments.get(0).equals("dbgit")) {
            throw usage();
        }
        String verb = arguments.size() >= 2 ? arguments.get(1) : "";
        return switch (verb) {
            case "init" -> new InitCommand(context);
            case "checkout" -> new CheckoutCommand(context, arguments.subList(2, arguments.size()));
            case "commit" -> new CommitCommand(context, arguments.subList(2, arguments.size()));
            case "branch" -> {
                if (arguments.size() != 2) {
                    throw usage();
                }
                yield new BranchCommand(context);
            }
            case "log" -> {
                if (arguments.size() != 2) {
                    throw usage();
                }
                yield new LogCommand(context);
            }
            case "reset" -> {
                if (arguments.size() != 3) {
                    throw usage();
                }
                yield new ResetCommand(context, arguments.get(2));
            }
            case "diff" -> {
                if (arguments.size() != 4) {
                    throw usage();
                }
                yield new DiffCommand(context, arguments.get(2), arguments.get(3));
            }
            case "merge" -> {
                if (arguments.size() != 3) {
                    throw usage();
                }
                yield new MergeCommand(context, arguments.get(2));
            }
            case "help" -> new HelpCommand(context, arguments.subList(2, arguments.size()));
            default -> throw usage();
        };
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Unknown or malformed command. Run 'dbgit help' to list all commands.");
    }
}
