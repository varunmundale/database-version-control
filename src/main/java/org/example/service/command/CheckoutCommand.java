package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit checkout <branch>} - switches to an already-existing branch. {@code dbgit checkout -b <branch>}
 * creates the branch first, delegating to {@link CreateBranchCommand}, then switches to it.
 */
public final class CheckoutCommand extends Command {
    private final List<String> arguments;

    /** {@code arguments} is everything after {@code dbgit checkout}: either {@code [branch]} or {@code [-b, branch]}. */
    public CheckoutCommand(CommandContext context, List<String> arguments) {
        super(context);
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }

    @Override
    public DbGitCommandResult execute() {
        if (arguments.size() == 2 && arguments.get(0).equals("-b")) {
            return new CreateBranchCommand(context, arguments.get(1)).execute();
        }
        if (arguments.size() == 1) {
            return checkoutExisting(arguments.get(0));
        }
        throw new IllegalArgumentException("Usage: dbgit checkout <branch> | dbgit checkout -b <branch>");
    }

    private DbGitCommandResult checkoutExisting(String branch) {
        if (!context.versioningService().branches().contains(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        context.repository().checkout(branch);
        return print(List.of("Switched to branch '" + branch + "'."));
    }
}
