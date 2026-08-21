package org.example.core;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.versioning.BranchMetadataStore;

import java.util.List;
import java.util.Objects;

/** Folds a branch's currently APPLIED changesets into one new commit, chained after its current HEAD. */
public final class BranchCommitter {
    private final BranchMetadataStore metadataStore;

    public BranchCommitter(BranchMetadataStore metadataStore) {
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    public CommitResult commit(String branch) {
        List<Long> appliedChangesetIds = metadataStore.changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .map(ChangeSet::id)
                .toList();
        if (appliedChangesetIds.isEmpty()) {
            return new CommitResult.NothingToCommit();
        }
        long commitId = metadataStore.commit(branch, appliedChangesetIds);
        return new CommitResult.Success(commitId, appliedChangesetIds.size());
    }
}
