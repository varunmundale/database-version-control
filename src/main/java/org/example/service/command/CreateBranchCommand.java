package org.example.service.command;

import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/** {@code dbgit checkout -b <branch>} - forks the current branch's database and switches to the new branch. */
public final class CreateBranchCommand extends Command {
    private final String branch;

    public CreateBranchCommand(CommandContext context, String branch) {
        super(context);
        this.branch = Objects.requireNonNull(branch, "branch must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        String fromBranch = context.repository().currentBranch();
        context.forker().fork(fromBranch, branch);
        context.repository().checkout(branch);
        return print(List.of("Switched to a new branch '" + branch + "'."));
    }
}
