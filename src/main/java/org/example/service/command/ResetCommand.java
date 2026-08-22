package org.example.service.command;

import org.example.core.resetter.ResetResult;
import org.example.core.resetter.Resetter;
import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit reset <commit>} - takes the current branch back to a commit, dropping its working set and rebuilding
 * its database from the truncated history. {@link Resetter} owns the actual work; this parses the commit id, which
 * is accepted both as {@code 3} and as the {@code #3} the other commands print.
 */
public final class ResetCommand extends Command {
    private final Resetter resetter;
    private final String commit;

    public ResetCommand(CommandContext context, String commit) {
        super(context);
        this.resetter = new Resetter(context.forker(), context.replayer(), context.connections());
        this.commit = Objects.requireNonNull(commit, "commit must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        String branch = context.repository().currentBranch();
        ResetResult result = resetter.reset(branch, commitId());
        return print(List.of(
                "Branch '" + branch + "' reset to commit #" + result.commitId() + ".",
                "Dropped " + result.droppedChangesets() + " working changeset(s); replayed "
                        + result.replayedChangesets() + " committed changeset(s) into a rebuilt database.",
                "Schema now has " + result.tableCount() + " table(s)."));
    }

    private long commitId() {
        String value = commit.startsWith("#") ? commit.substring(1) : commit;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Not a commit id: '" + commit + "'. Usage: dbgit reset <commit>");
        }
    }
}
