package org.example.core.versioning;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitParents;
import org.example.models.tracking.TrackedDatabase;
import org.example.repository.BranchMetadataRepository;
import org.example.repository.ChangesetRepository;
import org.example.repository.CommitRepository;
import org.example.repository.MetadataDatabase;
import org.example.repository.TrackedDatabaseRepository;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The default {@link VersioningService}, backed by the metadata repositories in
 * {@link org.example.repository}. Owns the business rules only - how a branch's history is reconstructed from
 * the shared commit graph, and what has to move together in a commit or a merge commit - leaving every row-level
 * read and write to a repository.
 */
public final class MetadataVersioningService implements VersioningService {
    private final MetadataDatabase database;
    private final BranchMetadataRepository branchRepository;
    private final CommitRepository commitRepository;
    private final ChangesetRepository changesetRepository;
    private final TrackedDatabaseRepository trackedDatabaseRepository;

    public MetadataVersioningService() {
        this.database = MetadataDatabase.getInstance();
        this.branchRepository = BranchMetadataRepository.getInstance();
        this.commitRepository = CommitRepository.getInstance();
        this.changesetRepository = ChangesetRepository.getInstance();
        this.trackedDatabaseRepository = TrackedDatabaseRepository.getInstance();
    }

    @Override
    public TrackedDatabase track(String branch, String host, int port, String database, String user) {
        TrackedDatabase tracked = TrackedDatabase.of(branch, host, port, database, user);
        this.database.execute(() -> trackedDatabaseRepository.upsert(tracked));
        return tracked;
    }

    @Override
    public Optional<TrackedDatabase> trackedDatabase(String branch) {
        return database.query(() -> trackedDatabaseRepository.find(branch));
    }

    @Override
    public List<String> branches() {
        return database.query(branchRepository::findAllNames);
    }

    @Override
    public boolean createBranch(String branchName, String forkedFrom) {
        return database.query(() -> branchRepository.insert(branchName, forkedFrom));
    }

    @Override
    public long stageChangeset(String branch, String ddl) {
        return database.query(() -> changesetRepository.insertPending(branch, ddl));
    }

    @Override
    public void markApplied(long changesetId) {
        database.execute(() -> changesetRepository.markApplied(changesetId));
    }

    @Override
    public List<ChangeSet> changesetsForBranch(String branch) {
        return database.query(() -> changesetRepository.findByBranch(branch));
    }

    @Override
    public List<ChangeSet> commitHistory(String branch) {
        return database.query(() -> {
            Long headCommitId = branchRepository.findHeadCommitId(branch);
            if (headCommitId == null) {
                return List.of();
            }

            List<Long> order = ancestryOrder(headCommitId, commitRepository.findAllParents());
            Map<Long, List<ChangeSet>> byCommit = changesetRepository.findGroupedByCommitId(order);

            List<ChangeSet> history = new ArrayList<>();
            for (Long commitId : order) {
                history.addAll(byCommit.getOrDefault(commitId, List.of()));
            }
            return history;
        });
    }

    @Override
    public long commit(String branch, List<Long> changesetIds) {
        if (changesetIds.isEmpty()) {
            throw new VersioningException("No applied changesets to commit for branch '" + branch + "'.");
        }
        return database.transaction("Could not commit branch '" + branch + "'", () -> {
            Long headCommitId = branchRepository.findHeadCommitId(branch);
            long commitId = commitRepository.insert(headCommitId, null);
            if (headCommitId != null) {
                commitRepository.updateNextCommitId(headCommitId, commitId);
            }
            branchRepository.updateHeadCommitId(branch, commitId);
            changesetRepository.markCommitted(changesetIds, commitId);
            return commitId;
        });
    }

    @Override
    public long createMergeCommit(String branch, String otherBranch) {
        return database.transaction("Could not create merge commit for branch '" + branch + "'", () -> {
            Long firstParent = branchRepository.findHeadCommitId(branch);
            Long secondParent = branchRepository.findHeadCommitId(otherBranch);
            if (secondParent == null) {
                throw new VersioningException("Branch '" + otherBranch + "' has no commits to merge.");
            }
            long commitId = commitRepository.insert(firstParent, secondParent);
            if (firstParent != null) {
                commitRepository.updateNextCommitId(firstParent, commitId);
            }
            commitRepository.updateNextCommitId(secondParent, commitId);
            branchRepository.updateHeadCommitId(branch, commitId);
            return commitId;
        });
    }

    private static List<Long> ancestryOrder(long headCommitId, Map<Long, CommitParents> parentsById) {
        List<Long> order = new ArrayList<>();
        collectAncestryOrder(headCommitId, parentsById, new LinkedHashSet<>(), order);
        return order;
    }

    /**
     * Walks a commit's ancestry in the order its schema was actually built up: the first parent's full history,
     * then anything reachable only through the second parent (a merge commit's contribution), then the commit
     * itself - mirroring how {@code createMergeCommit} physically replayed the second branch's diverged changesets
     * on top of the first's. A commit already {@code seen} (a common ancestor reached through both parents) is not
     * revisited, so it stays at its original position instead of being duplicated.
     */
    private static void collectAncestryOrder(Long commitId, Map<Long, CommitParents> parentsById, Set<Long> seen, List<Long> order) {
        if (commitId == null || seen.contains(commitId)) {
            return;
        }
        CommitParents parents = parentsById.get(commitId);
        collectAncestryOrder(parents.parentCommitId(), parentsById, seen, order);
        collectAncestryOrder(parents.secondParentCommitId(), parentsById, seen, order);
        if (seen.add(commitId)) {
            order.add(commitId);
        }
    }
}
