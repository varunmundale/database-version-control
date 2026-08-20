package org.example.service.command;

import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;
import org.example.service.DbGitCommandResult;
import org.example.versioning.BranchMetadataStore;

import java.io.IOException;
import java.util.List;

/** {@code dbgit commit} - folds the current branch's APPLIED changesets into one new commit. */
public final class CommitCommand extends Command {
    public CommitCommand(CommandContext context) {
        super(context);
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        String branch = context.repository().currentBranch();
        BranchMetadataStore metadataStore = context.branchFork().metadataStore();
        List<Long> appliedChangesetIds = metadataStore.changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .map(ChangeSet::id)
                .toList();
        if (appliedChangesetIds.isEmpty()) {
            return print(List.of("Nothing to commit for branch '" + branch + "'."));
        }
        long commitId = metadataStore.commit(branch, appliedChangesetIds);
        return print(List.of("Created commit #" + commitId + " for branch '" + branch + "' with "
                + appliedChangesetIds.size() + " changeset(s)."));
    }
}
