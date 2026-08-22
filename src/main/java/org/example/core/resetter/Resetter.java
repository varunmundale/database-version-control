package org.example.core.resetter;

import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.protocol.RequestContext;
import org.example.core.versioning.VersioningService;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Takes a branch back to a commit: its history is truncated there, its working set is discarded outright, and its
 * database is rebuilt from scratch by replaying what is left. Nothing is patched or undone - a reset branch is
 * exactly a branch forked at that commit, which is the only way to be sure the database matches the history when
 * dbgit has no notion of an inverse DDL statement.
 *
 * <p>The order is deliberate. The truncated history is replayed in memory first, so an incoherent one fails before
 * anything is destroyed. Metadata moves next, in one transaction. The database is rebuilt last, because that is
 * the step that cannot be transactional - if it fails part-way, the metadata already describes the target commit
 * and running the same reset again finishes the job.
 *
 * <p>{@code main} is refused. It tracks a real, pre-existing database rather than a scratchpad fork, and dropping
 * that to rebuild it from dbgit's history would destroy data dbgit never created and cannot replace.
 */
public final class Resetter {
    private static final String DEFAULT_BRANCH = "main";

    private final Forker forker;
    private final Replayer replayer;
    private final BranchConnections connections;

    private final BranchLocks locks;

    public Resetter(Forker forker, Replayer replayer, BranchConnections connections, BranchLocks locks) {
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
    }

    public ResetResult reset(RequestContext request, long commitId) {
        String branch = request.branch();
        if (branch.equals(DEFAULT_BRANCH)) {
            throw new IllegalStateException("Cannot reset branch 'main': it tracks a real database rather than a"
                    + " scratchpad fork, and resetting rebuilds a branch's database from scratch. Reset a forked"
                    + " branch instead, or point 'main' elsewhere with 'dbgit init'.");
        }

        // Global rather than per-branch, and a cold image pull takes minutes - so it happens before the branch
        // is locked, where it would stall every other command on that branch for the duration.
        forker.ensureBranchDatabasesRunning();

        try (BranchLease ignored = locks.acquire(branch)) {
            return reset(request, branch, commitId);
        }
    }

    private ResetResult reset(RequestContext request, String branch, long commitId) {
        VersioningService versioningService = forker.versioningService();
        List<ChangeSet> history = historyThrough(versioningService.commits(branch), branch, commitId);

        Map<String, TableModel> schema = replayer.replay(history);

        int dropped = versioningService.resetTo(branch, commitId);

        String database = BranchConnections.forkedDatabaseName(branch);
        forker.branchDatabases().dropDatabase(database);
        forker.branchDatabases().createDatabase(database);
        forker.branchDatabases().replay(connections.forBranch(request, branch), history);

        return new ResetResult(branch, commitId, dropped, history.size(), schema.size());
    }

    /**
     * Everything committed up to and including {@code commitId}, in replay order. Walking the branch's own commits
     * is what rejects a commit belonging to some other branch: it is a real commit, but not one this branch can be
     * taken back to.
     */
    private static List<ChangeSet> historyThrough(List<CommitEntry> commits, String branch, long commitId) {
        for (int index = 0; index < commits.size(); index++) {
            if (commits.get(index).commitId() == commitId) {
                return commits.subList(0, index + 1).stream()
                        .flatMap(entry -> entry.changesets().stream())
                        .toList();
            }
        }
        throw new IllegalArgumentException("Commit #" + commitId + " is not in branch '" + branch + "' history.");
    }
}
