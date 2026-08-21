package org.example.service.command;

import org.example.core.ChangesetStager;
import org.example.core.StageResult;
import org.example.service.DbGitCommandResult;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit add <ddl>} - validates the statement isn't blank, then delegates staging and applying it to
 * {@link ChangesetStager}. This is the only command that touches a live database.
 */
public final class AddCommand extends Command {
    private final ChangesetStager changesetStager;
    private final String ddl;

    public AddCommand(CommandContext context, String ddl) {
        super(context);
        this.changesetStager = new ChangesetStager(context.branchFork(), context.schemaReplayer());
        this.ddl = Objects.requireNonNull(ddl, "ddl must not be null");
    }

    @Override
    public DbGitCommandResult execute() throws IOException {
        String statement = ddl.strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Usage: dbgit add <DDL statement>");
        }

        String branch = context.repository().currentBranch();
        StageResult result = changesetStager.stage(branch, statement);
        return print(List.of("Applied changeset #" + result.changesetId() + " for branch '" + branch + "': table '"
                + result.tableName() + "' now has " + result.columnCount() + " column(s)."));
    }
}
