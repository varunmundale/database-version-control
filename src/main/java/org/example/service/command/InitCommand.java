package org.example.service.command;

import org.example.config.ConnectionSettings;
import org.example.connectors.SqlConnector;
import org.example.models.tracking.TrackedDatabase;
import org.example.service.DbGitCommandResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@code dbgit init --host <h> --port <p> --database <d> --user <u> --password <w>} - points {@code main} at a
 * real, already-existing database, so that everything committed on {@code main} is applied there rather than to a
 * throwaway scratchpad.
 *
 * <p>The details are split deliberately. The metadata store gets a {@link TrackedDatabase}: a signature plus host,
 * port, database and user, so any workspace can see what {@code main} is supposed to point at. The password is
 * written only to this workspace's {@code .dbgit/config.json}, which is local and gitignored - initialising a
 * branch never puts a credential anywhere shared.
 *
 * <p>Idempotent: initialising against the same database twice signs the same and simply refreshes the stored
 * connection. Pointing it somewhere else repoints {@code main}, keeping its commit history.
 */
public final class InitCommand extends Command {
    private static final String BRANCH = "main";
    private static final int DEFAULT_PORT = 5432;

    private final List<String> arguments;

    public InitCommand(CommandContext context, List<String> arguments) {
        super(context);
        this.arguments = List.copyOf(Objects.requireNonNull(arguments, "arguments must not be null"));
    }

    @Override
    public DbGitCommandResult execute() {
        ConnectionSettings settings = parse();
        verifyReachable(settings);

        Optional<TrackedDatabase> previous = context.versioningService().trackedDatabase(BRANCH);
        TrackedDatabase tracked = context.versioningService()
                .track(BRANCH, settings.host(), settings.port(), settings.database(), settings.user());
        context.repository().track(BRANCH, settings);

        return print(List.of(describe(previous, tracked), "Signature: " + tracked.signature() + "."));
    }

    private static String describe(Optional<TrackedDatabase> previous, TrackedDatabase tracked) {
        if (previous.isEmpty()) {
            return "Branch 'main' now tracks " + tracked.describe() + ".";
        }
        if (previous.get().signature().equals(tracked.signature())) {
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

    private ConnectionSettings parse() {
        String host = null;
        String database = null;
        String user = null;
        String password = "";
        int port = DEFAULT_PORT;

        List<String> unknown = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String flag = arguments.get(index);
            switch (flag) {
                case "--host" -> host = value(flag, ++index);
                case "--port" -> port = Integer.parseInt(value(flag, ++index));
                case "--database" -> database = value(flag, ++index);
                case "--user" -> user = value(flag, ++index);
                case "--password" -> password = value(flag, ++index);
                default -> unknown.add(flag);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s) " + unknown + ". " + usage());
        }
        if (host == null || database == null || user == null) {
            throw new IllegalArgumentException("--host, --database and --user are all required. " + usage());
        }
        return new ConnectionSettings(host, port, user, password, database);
    }

    private String value(String flag, int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(flag + " needs a value. " + usage());
        }
        return arguments.get(index);
    }

    private static String usage() {
        return "Usage: dbgit init --host <host> [--port " + DEFAULT_PORT
                + "] --database <database> --user <user> [--password <password>]";
    }
}
