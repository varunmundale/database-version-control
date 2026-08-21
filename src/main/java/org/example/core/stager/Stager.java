package org.example.core.stager;

import org.example.core.forker.Forker;
import org.example.core.replayer.Replayer;
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
    private static final String SCHEMA = "public";

    private final Forker forker;
    private final Replayer replayer;

    public Stager(Forker forker, Replayer replayer) {
        this.forker = Objects.requireNonNull(forker, "forker must not be null");
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
    }

    public StageResult stage(String branch, String statement) {
        VersioningService versioningService = forker.versioningService();

        String tableName = replayer.tableName(statement);
        Map<String, TableModel> currentSchema = replayer.replay(versioningService.branchHistory(branch));
        TableModel updated = replayer.apply(SCHEMA, statement, currentSchema.get(tableName));

        long changesetId = versioningService.stageChangeset(branch, statement);

        forker.branchDatabases().apply(forker.defaultDatabaseName(branch), statement);

        versioningService.markApplied(changesetId);
        return new StageResult(changesetId, updated.name(), updated.columns().size());
    }
}
