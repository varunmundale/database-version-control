package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit help} - lists every dispatchable command's synopsis and one-line description. {@code dbgit help
 * <command>} prints just that one, in full.
 *
 * <p>{@link #CATALOG} is the single place the documentation is assembled, but it holds no text of its own - each
 * entry is another command's own {@code USAGE} constant, so this list and what {@link CommandFactory} actually
 * dispatches can't drift apart. {@code CreateBranchCommand} is deliberately absent: {@code checkout -b} is not its
 * own verb, and {@link CheckoutCommand#USAGE} already documents it.
 */
public final class HelpCommand extends Command {
    public static final CommandUsage USAGE = new CommandUsage("help", "dbgit help [<command>]",
            "Lists every command's usage, or describes one command in detail.");

    private static final List<CommandUsage> CATALOG = List.of(
            InitCommand.USAGE,
            CheckoutCommand.USAGE,
            AddCommand.USAGE,
            CommitCommand.USAGE,
            BranchCommand.USAGE,
            LogCommand.USAGE,
            ResetCommand.USAGE,
            DiffCommand.USAGE,
            MergeCommand.USAGE,
            HelpCommand.USAGE);

    private final List<String> arguments;

    public HelpCommand(CommandContext context, List<String> arguments) {
        super(context);
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }

    @Override
    public DbGitCommandResult execute() {
        if (arguments.isEmpty()) {
            return print(listAll());
        }
        if (arguments.size() == 1) {
            return print(describeOne(arguments.get(0)));
        }
        throw new IllegalArgumentException("Usage: " + USAGE.synopsis());
    }

    private List<String> listAll() {
        List<String> lines = new ArrayList<>();
        lines.add("dbgit commands:");
        int widest = CATALOG.stream().mapToInt(usage -> usage.synopsis().length()).max().orElse(0);
        for (CommandUsage usage : CATALOG) {
            lines.add("  " + pad(usage.synopsis(), widest) + "  " + usage.description());
        }
        lines.add("Run 'dbgit help <command>' for detail on one command.");
        return lines;
    }

    private List<String> describeOne(String verb) {
        return CATALOG.stream()
                .filter(usage -> usage.verb().equals(verb))
                .findFirst()
                .map(usage -> List.of(usage.synopsis(), usage.description()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown command '" + verb + "'. Run 'dbgit help' to list all commands."));
    }

    private static String pad(String text, int width) {
        return text + " ".repeat(Math.max(0, width - text.length()));
    }
}
