package org.example.core.resetter;

import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.request.RequestContext;
import org.example.core.versioning.VersioningService;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitEntry;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Takes a branch back to a commit by truncating its history there, discarding its working set, and rebuilding its
 * database from scratch - dbgit has no inverse DDL, so a reset is exactly a fresh fork at that commit. Order is
 * deliberate: replay in memory first (fails before anything is destroyed), then metadata in one transaction, then
 * the non-transactional database rebuild last (a failure there leaves metadata already pointing at the target, so
 * re-running finishes the job). {@code main} is refused since it tracks a real database dbgit didn't create.
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

        // Outside the lock: a cold image pull takes minutes and would stall every other command on this branch.
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

    /** Everything committed up to and including {@code commitId}, in replay order; rejects a commit from another branch. */
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
