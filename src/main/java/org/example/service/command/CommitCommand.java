package org.example.service.command;

import org.example.core.BranchCommitter;
import org.example.core.CommitResult;
import org.example.service.DbGitCommandResult;

import java.io.IOException;
import java.util.List;

/** {@code dbgit commit} - delegates folding the current branch's APPLIED changesets into one new commit to {@link BranchCommitter}. */
public final class CommitCommand extends Command {
    private final BranchCommitter branchCommitter;

    public CommitCommand(CommandContext context) {
        super(context);
        this.branchCommitter = new BranchCommitter(context.branchFork().metadataStore());
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        String branch = context.repository().currentBranch();
        CommitResult result = branchCommitter.commit(branch);
        return switch (result) {
            case CommitResult.NothingToCommit ignored -> print(List.of("Nothing to commit for branch '" + branch + "'."));
            case CommitResult.Success success -> print(List.of("Created commit #" + success.commitId() + " for branch '"
                    + branch + "' with " + success.changesetCount() + " changeset(s)."));
        };
    }
}
