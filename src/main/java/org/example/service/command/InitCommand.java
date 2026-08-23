package org.example.service.command;

import org.example.config.ConnectionSettings;
import org.example.config.TrackedDatabaseConfig;
import org.example.connectors.SqlConnector;
import org.example.core.locking.BranchLease;
import org.example.protocol.RequestContext;
import org.example.service.DbGitCommandResult;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * {@code dbgit init} - points {@code main} at a real, already-existing database, so that everything committed on
 * {@code main} is applied there rather than to a throwaway scratchpad.
 *
 * <p>The connection arrives on the request, parsed and held by the caller's own workspace; the daemon never stores
 * it. All this command records is a {@link TrackedDatabaseConfig} in the metadata store: a signature plus host,
 * port, database and user, so any workspace can see what {@code main} is supposed to point at. The password is not
 * part of that record and has nowhere shared to live.
 *
 * <p>Idempotent: initialising against the same database twice signs the same. Pointing it somewhere else repoints
 * {@code main}, keeping its commit history.
 */
public final class InitCommand extends Command {
    public static final CommandUsage USAGE = new CommandUsage("init",
            "dbgit init --host <host> [--port 5432] --database <database> --user <user> [--password <password>]",
            "Points 'main' at a real, already-existing database it tracks. Idempotent: re-running it against the "
                    + "same target just refreshes the stored connection.");

    private static final String BRANCH = RequestContext.DEFAULT_BRANCH;

    public InitCommand(CommandContext context) {
        super(context);
    }

    @Override
    public DbGitCommandResult execute() {
        ConnectionSettings settings = context.request().trackedDatabaseIfConfigured()
                .orElseThrow(() -> new IllegalArgumentException("No connection details were sent with 'dbgit init'. "
                        + "Usage: dbgit init --host <host> [--port 5432] --database <database> --user <user>"
                        + " [--password <password>]"));
        verifyReachable(settings);

        try (BranchLease ignored = context.locks().acquire(BRANCH)) {
            Optional<TrackedDatabaseConfig> previous = context.versioningService().trackedDatabase(BRANCH);
            TrackedDatabaseConfig tracked = context.versioningService().track(BRANCH, settings);

            return print(List.of(describe(previous, tracked), "Signature: " + tracked.signature() + "."));
        }
    }

    private static String describe(Optional<TrackedDatabaseConfig> previous, TrackedDatabaseConfig tracked) {
        if (previous.isEmpty()) {
            return "Branch 'main' now tracks " + tracked.describe() + ".";
        }
        if (previous.get().tracksSameDatabaseAs(tracked)) {
            return "Branch 'main' already tracks " + tracked.describe() + "; connection details refreshed.";
        }
        return "Branch 'main' now tracks " + tracked.describe() + " (was " + previous.get().describe()
                + "); commit history kept.";
    }

    /** Connecting once here means a typo or a bad password fails now, rather than on the next {@code dbgit add}. */
    private void verifyReachable(ConnectionSettings settings) {
        try (SqlConnector ignored = context.forker().branchDatabases().connect(settings)) {
            // reachable
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Could not connect to " + settings.database() + "@" + settings.host()
                    + ":" + settings.port() + " as '" + settings.user() + "': " + exception.getMessage()
                    + ". Nothing was recorded.", exception);
        }
    }
}
