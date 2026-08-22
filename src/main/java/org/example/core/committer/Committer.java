package org.example.core.committer;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitMetadata;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;

/** Folds a branch's currently APPLIED changesets into one new commit, chained after its current HEAD. */
public final class Committer {
    private final VersioningService versioningService;

    public Committer(VersioningService versioningService) {
        this.versioningService = Objects.requireNonNull(versioningService, "versioningService must not be null");
    }

    public CommitResult commit(String branch, CommitMetadata metadata) {
        List<Long> appliedChangesetIds = versioningService.appliedChangesets(branch).stream()
                .map(ChangeSet::id)
                .toList();
        if (appliedChangesetIds.isEmpty()) {
            return new CommitResult.NothingToCommit();
        }
        long commitId = versioningService.commit(branch, appliedChangesetIds, metadata);
        return new CommitResult.Success(commitId, appliedChangesetIds.size());
    }
}
