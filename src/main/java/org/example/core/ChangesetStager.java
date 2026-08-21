package org.example.core;

import org.example.branch.BranchFork;
import org.example.connectors.SqlConnector;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.versioning.BranchMetadataStore;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stages one DDL statement on a branch: previews its effect purely in memory (replaying the branch's history and
 * applying the new statement on top, which is what gives {@code dbgit add} its schema validation), stages it as a
 * changeset, executes it for real against the branch's own database, then marks it applied.
 */
public final class ChangesetStager {
    private static final String SCHEMA = "public";

    private final BranchFork branchFork;
    private final SchemaReplayer schemaReplayer;

    public ChangesetStager(BranchFork branchFork, SchemaReplayer schemaReplayer) {
        this.branchFork = Objects.requireNonNull(branchFork, "branchFork must not be null");
        this.schemaReplayer = Objects.requireNonNull(schemaReplayer, "schemaReplayer must not be null");
    }

    public StageResult stage(String branch, String statement) {
        BranchMetadataStore metadataStore = branchFork.metadataStore();

        String tableName = schemaReplayer.tableName(statement);
        Map<String, TableModel> currentSchema = schemaReplayer.replay(branchHistory(branch, metadataStore));
        TableModel updated = schemaReplayer.apply(SCHEMA, statement, currentSchema.get(tableName));

        long changesetId = metadataStore.stageChangeset(branch, statement);

        String database = branchFork.defaultDatabaseName(branch);
        try (SqlConnector connector = branchFork.connect(database)) {
            connector.execute(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not apply DDL to database '" + database + "': " + exception.getMessage(), exception);
        }

        metadataStore.markApplied(changesetId);
        return new StageResult(changesetId, updated.name(), updated.columns().size());
    }

    /**
     * The changesets that make up a branch's current schema, in order: its inherited/committed history (reached
     * via the shared commit chain, regardless of which branch originally created each commit) followed by its own
     * not-yet-committed applied changesets.
     */
    private static List<ChangeSet> branchHistory(String branch, BranchMetadataStore metadataStore) {
        List<ChangeSet> history = new ArrayList<>(metadataStore.commitHistory(branch));
        history.addAll(metadataStore.changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .toList());
        return history;
    }
}
