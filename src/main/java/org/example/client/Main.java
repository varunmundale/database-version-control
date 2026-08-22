package org.example.client;

import org.example.config.ConnectionArguments;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code dbgit} CLI. Beyond forwarding a command to the daemon it owns the two pieces of state that are the
 * user's rather than the service's: which branch this workspace is on, and how it reaches the database
 * {@code main} tracks. Both are written only after the daemon has accepted the command, so a rejected
 * {@code checkout} or an unreachable {@code init} leaves the workspace as it was.
 */
public class Main {
    /** Where {@code .dbgit} lives. The wrapper script sets this to the directory the user actually ran from. */
    private static final String WORKSPACE_VARIABLE = "DBGIT_WORKSPACE";

    public static void main(String[] args) {
        ClientWorkspace workspace = new ClientWorkspace(workspaceDirectory());
        DbGitClient client = new DbGitClient();
        List<String> arguments = List.of(args);
        System.exit(run(workspace, client, arguments));
    }

    static int run(ClientWorkspace workspace, DbGitClient client, List<String> arguments) {
        String command = arguments.isEmpty() ? "" : arguments.getFirst();
        return switch (command) {
            case "add" -> client.runAdd(workspace.requestContext(), readStdin(), System.out, System.err);
            case "init" -> init(workspace, client, arguments);
            case "checkout" -> checkout(workspace, client, arguments);
            default -> client.run(workspace.requestContext(), arguments, System.out, System.err);
        };
    }

    /**
     * The connection is parsed here rather than in the daemon, because it is the header that carries it now. Only
     * once the daemon confirms it can reach the database is it written to the workspace.
     */
    private static int init(ClientWorkspace workspace, DbGitClient client, List<String> arguments) {
        ConnectionSettings settings;
        try {
            settings = ConnectionArguments.parse(arguments.subList(1, arguments.size()));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage());
            return 1;
        }

        RequestContext request = new RequestContext(System.getProperty("user.name"),
                workspace.currentBranch(), settings);
        int exitCode = client.run(request, List.of("init"), System.out, System.err);
        if (exitCode == 0) {
            workspace.track(RequestContext.DEFAULT_BRANCH, settings);
        }
        return exitCode;
    }

    /** {@code checkout} is the one command whose effect is local: HEAD moves only once the daemon agrees. */
    private static int checkout(ClientWorkspace workspace, DbGitClient client, List<String> arguments) {
        int exitCode = client.run(workspace.requestContext(), arguments, System.out, System.err);
        if (exitCode == 0) {
            branchName(arguments).ifPresent(workspace::checkout);
        }
        return exitCode;
    }

    /** {@code checkout <branch>} or {@code checkout -b <branch>}; anything else the daemon has already rejected. */
    private static java.util.Optional<String> branchName(List<String> arguments) {
        if (arguments.size() == 2) {
            return java.util.Optional.of(arguments.get(1));
        }
        if (arguments.size() == 3 && arguments.get(1).equals("-b")) {
            return java.util.Optional.of(arguments.get(2));
        }
        return java.util.Optional.empty();
    }

    /**
     * Read from the environment rather than from the working directory, because the {@code dbgit} wrapper has to
     * {@code cd} to the project to run Maven - which would otherwise make every caller on the machine share one
     * workspace, and with it one current branch. That is exactly what the daemon stopped doing.
     */
    private static Path workspaceDirectory() {
        String configured = System.getenv(WORKSPACE_VARIABLE);
        return Path.of(configured == null || configured.isBlank() ? "." : configured).toAbsolutePath().normalize();
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
