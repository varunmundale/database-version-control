package org.example.service.command;

import org.example.connector.SqlConnector;
import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;
import org.example.service.DbGitCommandResult;
import org.example.versioning.BranchMetadataStore;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code dbgit add <ddl>} - previews the statement's effect purely in memory (replaying the branch's history and
 * applying the new statement on top), stages it, then executes it for real against the branch's database. This is
 * the only command that touches a live database.
 */
public final class AddCommand extends Command {
    private final String ddl;

    public AddCommand(CommandContext context, String ddl) {
        super(context);
        this.ddl = Objects.requireNonNull(ddl, "ddl must not be null");
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        String statement = ddl.strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Usage: dbgit add <DDL statement>");
        }

        String branch = context.repository().currentBranch();
        BranchMetadataStore metadataStore = context.branchFork().metadataStore();

        String tableName = context.schemaReplayer().tableName(statement);
        Map<String, TableModel> currentSchema = context.schemaReplayer().replay(branchHistory(branch, metadataStore));
        TableModel updated = context.schemaReplayer().apply("public", statement, currentSchema.get(tableName));

        long changesetId = metadataStore.stageChangeset(branch, statement);

        String database = context.branchFork().defaultDatabaseName(branch);
        try (SqlConnector connector = context.branchFork().connect(database)) {
            connector.execute(statement);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not apply DDL to database '" + database + "': " + exception.getMessage(), exception);
        }

        metadataStore.markApplied(changesetId);
        return print(List.of("Applied changeset #" + changesetId + " for branch '" + branch + "': table '" + updated.name()
                + "' now has " + updated.columns().size() + " column(s)."));
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
