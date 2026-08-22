package org.example.service.command;

import org.example.core.merger.Merger;
import org.example.core.merger.MergeResult;
import org.example.service.DbGitCommandResult;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit merge <other-branch>} - validates the two branches, then delegates the actual merge algorithm to
 * {@link Merger} and turns its {@link MergeResult} into either printed output or a rejection.
 */
public final class MergeCommand extends Command {
    private final Merger merger;
    private final String otherBranch;

    public MergeCommand(CommandContext context, String otherBranch) {
        super(context);
        this.merger = new Merger(context.forker(), context.replayer(), context.connections());
        this.otherBranch = Objects.requireNonNull(otherBranch, "otherBranch must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        String currentBranch = context.branch();
        VersioningService versioningService = context.versioningService();
        if (!versioningService.branches().contains(otherBranch)) {
            throw new IllegalArgumentException("Unknown branch: " + otherBranch);
        }
        if (otherBranch.equals(currentBranch)) {
            throw new IllegalArgumentException("Cannot merge branch '" + currentBranch + "' into itself.");
        }

        MergeResult result = merger.merge(context.request(), otherBranch);
        return switch (result) {
            case MergeResult.AlreadyUpToDate ignored -> print(List.of("Already up to date."));
            case MergeResult.Conflict conflict -> throw new IllegalStateException("Cannot merge '" + otherBranch + "' into '"
                    + currentBranch + "': conflicting changes to " + String.join("; ", conflict.conflicts()) + ".");
            case MergeResult.Success success -> print(List.of(
                    "Merged '" + otherBranch + "' into '" + currentBranch + "' as commit #" + success.commitId()
                            + ", applying " + success.appliedChangesetCount() + " changeset(s).",
                    "Validated via staging branch '" + success.stagingBranch() + "'."));
        };
    }
}
