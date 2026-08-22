package org.example.service;

import org.example.config.ConnectionArguments;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;

import java.util.Arrays;
import java.util.List;

/**
 * One client's session with the daemon, for tests that drive it without a socket. Stands in for what
 * {@code org.example.client.Main} does: it remembers which branch this caller is on and which database its
 * workspace tracks, and sends both with every command - because the daemon no longer keeps either.
 *
 * <p>Like the real client it updates that local state only after the daemon accepts the command, so a rejected
 * {@code checkout} leaves the session where it was.
 */
final class DaemonSession {
    private final DbGitCommandListener listener;
    private final String user;
    private String branch = RequestContext.DEFAULT_BRANCH;
    private ConnectionSettings trackedDatabase;

    DaemonSession(DbGitCommandListener listener, String user) {
        this.listener = listener;
        this.user = user;
    }

    String branch() {
        return branch;
    }

    RequestContext request() {
        return new RequestContext(user, branch, trackedDatabase);
    }

    DbGitCommandResult execute(String commandLine) {
        List<String> arguments = Arrays.stream(commandLine.trim().split("\\s+")).toList();
        if (isInitWithConnection(arguments)) {
            return init(arguments);
        }
        DbGitCommandResult result = listener.execute(request(), commandLine);
        checkedOutBranch(arguments).ifPresent(checkedOut -> branch = checkedOut);
        return result;
    }

    DbGitCommandResult add(String ddl) {
        return listener.add(request(), ddl);
    }

    /** The connection flags are the client's to parse now; the daemon only ever sees the parsed result. */
    private DbGitCommandResult init(List<String> arguments) {
        ConnectionSettings settings = ConnectionArguments.parse(arguments.subList(2, arguments.size()));
        DbGitCommandResult result = listener.execute(new RequestContext(user, branch, settings), "dbgit init");
        trackedDatabase = settings;
        return result;
    }

    private static boolean isInitWithConnection(List<String> arguments) {
        return arguments.size() > 2 && arguments.get(1).equals("init");
    }

    private static java.util.Optional<String> checkedOutBranch(List<String> arguments) {
        if (arguments.size() < 2 || !arguments.get(1).equals("checkout")) {
            return java.util.Optional.empty();
        }
        if (arguments.size() == 3) {
            return java.util.Optional.of(arguments.get(2));
        }
        if (arguments.size() == 4 && arguments.get(2).equals("-b")) {
            return java.util.Optional.of(arguments.get(3));
        }
        return java.util.Optional.empty();
    }
}
