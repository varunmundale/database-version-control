package org.example.service.command;

import org.example.core.locking.BranchLease;
import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/** {@code dbgit checkout -b <branch>} - forks the current branch's database and switches to the new branch. */
public final class CreateBranchCommand extends Command {
    /** Not its own dispatchable verb - {@code checkout}'s {@link CheckoutCommand#USAGE} covers {@code -b}. */
    public static final CommandUsage USAGE = new CommandUsage("checkout -b", "dbgit checkout -b <branch>",
            "Forks the current branch's database into a new branch and switches to it.");

    private final String branch;

    public CreateBranchCommand(CommandContext context, String branch) {
        super(context);
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        // Bringing the shared container up is global and can take minutes on a cold pull; doing it before the
        // locks are taken keeps it from blocking every other command on these two branches.
        context.forker().ensureBranchDatabasesRunning();
        try (BranchLease ignored = context.locks().acquire(context.branch(), branch)) {
            context.forker().fork(context.branch(), branch);
        }
        return print(List.of("Switched to a new branch '" + branch + "'."));
    }
}
