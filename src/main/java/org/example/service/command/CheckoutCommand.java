package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** {@code dbgit checkout <branch>} - switches to an already-existing branch. */
public final class CheckoutCommand extends Command {
    private final String branch;

    public CheckoutCommand(CommandContext context, String branch) {
        super(context);
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        if (!context.branchFork().metadataStore().branches().contains(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        context.repository().checkout(branch);
        return print(List.of("Switched to branch '" + branch + "'."));
    }
}
