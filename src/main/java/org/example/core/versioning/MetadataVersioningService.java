package org.example.core.versioning;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitEntry;
import org.example.models.versioning.CommitMetadata;
import org.example.config.ConnectionSettings;
import org.example.config.TrackedDatabaseConfig;
import org.example.repository.BranchMetadataRepository;
import org.example.repository.ChangesetRepository;
import org.example.repository.CommitRepository;
import org.example.repository.MetadataDatabase;
import org.example.repository.TrackedDatabaseRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The default {@link VersioningService}, backed by the metadata repositories in {@link org.example.repository}.
 * Owns the business rules only - reconstructing a branch's history, what must move together in a commit or merge
 * commit - leaving row-level reads/writes to the repositories.
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
    public TrackedDatabaseConfig track(String branch, ConnectionSettings settings) {
        TrackedDatabaseConfig tracked = TrackedDatabaseConfig.of(branch, settings);
        database.execute(() -> trackedDatabaseRepository.upsert(tracked));
        return tracked;
    }

    @Override
    public Optional<TrackedDatabaseConfig> trackedDatabase(String branch) {
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
    public void deleteBranch(String branch) {
        database.execute(() -> branchRepository.delete(branch));
    }

    @Override
    public void discardChangeset(long changesetId) {
        database.execute(() -> changesetRepository.delete(changesetId));
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
    public List<CommitEntry> commits(String branch) {
        return database.snapshot("Could not read the history of branch '" + branch + "'", () -> {
            Long headCommitId = branchRepository.findHeadCommitId(branch);
            if (headCommitId == null) {
                return List.<CommitEntry>of();
            }

            Map<Long, Commit> commitsById = commitRepository.findAll();
            List<Long> order = new CommitGraph(commitsById).topologicalOrder(headCommitId);
            Map<Long, List<ChangeSet>> byCommit = changesetRepository.findGroupedByCommitId(order);

            List<CommitEntry> entries = new ArrayList<>();
            for (Long commitId : order) {
                entries.add(new CommitEntry(commitsById.get(commitId), byCommit.getOrDefault(commitId, List.of())));
            }
            return entries;
        });
    }

    @Override
    public long commit(String branch, List<Long> changesetIds, CommitMetadata metadata) {
        if (changesetIds.isEmpty()) {
            throw new VersioningException("No applied changesets to commit for branch '" + branch + "'.");
        }
        return database.transaction("Could not commit branch '" + branch + "'", () -> {
            Long headCommitId = branchRepository.findHeadCommitId(branch);
            long commitId = commitRepository.insert(branch, headCommitId, null, metadata);
            moveHead(branch, headCommitId, commitId);
            changesetRepository.markCommitted(changesetIds, commitId);
            return commitId;
        });
    }

    @Override
    public long createMergeCommit(String branch, String otherBranch, CommitMetadata metadata) {
        return database.transaction("Could not create merge commit for branch '" + branch + "'", () -> {
            Long firstParent = branchRepository.findHeadCommitId(branch);
            Long secondParent = branchRepository.findHeadCommitId(otherBranch);
            if (secondParent == null) {
                throw new VersioningException("Branch '" + otherBranch + "' has no commits to merge.");
            }
            long commitId = commitRepository.insert(branch, firstParent, secondParent, metadata);
            moveHead(branch, firstParent, commitId);
            return commitId;
        });
    }

    /**
     * Both halves move together or not at all: a branch whose HEAD moved back but whose working set survived would
     * replay changesets belonging to a schema it no longer has.
     */
    @Override
    public int resetTo(String branch, long commitId) {
        return database.transaction("Could not reset branch '" + branch + "'", () -> {
            Long headCommitId = branchRepository.findHeadCommitId(branch);
            if (headCommitId == null) {
                throw new VersioningException("Branch '" + branch + "' has no commits to reset to.");
            }
            if (!new CommitGraph(commitRepository.findAll()).isAncestor(commitId, headCommitId)) {
                throw new VersioningException(
                        "Commit #" + commitId + " is not in branch '" + branch + "' history.");
            }
            moveHead(branch, headCommitId, commitId);
            return changesetRepository.deleteUncommitted(branch);
        });
    }

    /**
     * Moves a branch's HEAD, refusing if it is no longer where this operation read it. A branch lock makes that
     * impossible in normal running; this is what turns a lock that was somehow missed into an error the caller
     * sees, rather than a commit that silently becomes unreachable.
     */
    private void moveHead(String branch, Long expectedHeadCommitId, long commitId) {
        if (branchRepository.updateHeadCommitId(branch, expectedHeadCommitId, commitId) == 0) {
            throw new VersioningException("Branch '" + branch + "' moved concurrently; nothing was changed."
                    + " Re-read its history and try again.");
        }
    }
}
