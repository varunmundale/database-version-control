package org.example.service.command;

import org.example.core.stager.Stager;
import org.example.core.stager.StageResult;
import org.example.service.DbGitCommandResult;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit add <ddl>} - validates the statement isn't blank, then delegates staging and applying it to
 * {@link Stager}. This is the only command that touches a live database.
 */
public final class AddCommand extends Command {
    private final Stager stager;
    private final String ddl;

    public AddCommand(CommandContext context, String ddl) {
        super(context);
        this.stager = new Stager(context.forker(), context.replayer(), context.connections());
        this.ddl = Objects.requireNonNull(ddl, "ddl must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        String statement = ddl.strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Usage: dbgit add <DDL statement>");
        }

        String branch = context.branch();
        StageResult result = stager.stage(context.request(), statement);
        return print(List.of("Applied changeset #" + result.changesetId() + " for branch '" + branch + "': table '"
                + result.tableName() + "' now has " + result.columnCount() + " column(s)."));
    }
}
