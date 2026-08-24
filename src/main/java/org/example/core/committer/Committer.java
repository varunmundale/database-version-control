package org.example.core.committer;

import org.example.models.versioning.ChangeSet;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.models.versioning.CommitMetadata;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;

/** Folds a branch's currently APPLIED changesets into one new commit, chained after its current HEAD. */
public final class Committer {
    private final VersioningService versioningService;
    private final BranchLocks locks;

    public Committer(VersioningService versioningService, BranchLocks locks) {
        this.versioningService = Objects.requireNonNull(versioningService, "versioningService must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
    }

    /** Under the branch's lock: reading applied changesets and folding them into a commit are two transactions. */
    public CommitResult commit(String branch, CommitMetadata metadata) {
        try (BranchLease ignored = locks.acquire(branch)) {
            return committed(branch, metadata);
        }
    }

    private CommitResult committed(String branch, CommitMetadata metadata) {
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
