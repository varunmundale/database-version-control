package org.example.core.stager;

import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLease;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.request.RequestContext;
import org.example.models.schema.TableModel;
import org.example.core.versioning.VersioningService;

import java.util.Map;
import java.util.Objects;

/**
 * Stages one DDL statement on a branch: previews its effect purely in memory (replaying the branch's history and
 * applying the new statement on top, which is what gives {@code dbgit add} its schema validation), stages it as a
 * changeset, executes it for real against the branch's own database, then marks it applied.
 */
public final class Stager {
    private final Forker forker;
    private final Replayer replayer;
    private final BranchConnections connections;
    private final BranchLocks locks;

    public Stager(Forker forker, Replayer replayer, BranchConnections connections, BranchLocks locks) {
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.locks = Objects.requireNonNull(locks, "locks must not be null");
    }

    /**
     * Held under the branch's lock from the first read to the last write. The staged changeset, the DDL that
     * actually runs, and the row that records it having run are three separate transactions with an
     * irreversible statement in the middle, so nothing but a lock spanning all of it keeps two concurrent adds
     * from validating against the same past and executing in an order their changeset ids do not describe.
     */
    public StageResult stage(RequestContext request, String statement) {
        String branch = request.branch();
        try (BranchLease ignored = locks.acquire(branch)) {
            return staged(request, branch, statement);
        }
    }

    private StageResult staged(RequestContext request, String branch, String statement) {
        VersioningService versioningService = forker.versioningService();

        String tableName = replayer.tableName(statement);
        Map<String, TableModel> currentSchema = replayer.replay(versioningService.branchHistory(branch));
        TableModel updated = replayer.apply(currentSchema, statement);

        long changesetId = versioningService.stageChangeset(branch, statement);

        try {
            forker.branchDatabases().apply(connections.forBranch(request, branch), statement);
        } catch (RuntimeException exception) {
            // The row was written before the statement ran. Left behind it would sit at PENDING forever:
            // excluded from appliedChangesets so it can never be committed, yet counted in the working set
            // and destroyed by the next reset. It describes something that never happened, so it goes.
            versioningService.discardChangeset(changesetId);
            throw exception;
        }

        versioningService.markApplied(changesetId);
        return updated == null
                ? new StageResult.Dropped(changesetId, tableName)
                : new StageResult.Applied(changesetId, updated.name(), updated.columns().size());
    }
}
