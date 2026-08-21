package org.example.service.command;

import org.example.core.committer.Committer;
import org.example.core.committer.CommitResult;
import org.example.service.DbGitCommandResult;

import java.util.List;

/** {@code dbgit commit} - delegates folding the current branch's APPLIED changesets into one new commit to {@link Committer}. */
public final class CommitCommand extends Command {
    private final Committer committer;

    public CommitCommand(CommandContext context) {
        super(context);
        this.committer = new Committer(context.versioningService());
    }

    @Override
    public DbGitCommandResult execute() {
        String branch = context.repository().currentBranch();
        CommitResult result = committer.commit(branch);
        return switch (result) {
            case CommitResult.NothingToCommit ignored -> print(List.of("Nothing to commit for branch '" + branch + "'."));
            case CommitResult.Success success -> print(List.of("Created commit #" + success.commitId() + " for branch '"
                    + branch + "' with " + success.changesetCount() + " changeset(s)."));
        };
    }
}
