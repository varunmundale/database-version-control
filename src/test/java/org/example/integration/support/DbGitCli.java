package org.example.integration.support;

import org.example.client.ClientWorkspace;
import org.example.client.DbGitClient;
import org.example.config.ConnectionArguments;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * One user at a {@code dbgit} prompt: a real {@link ClientWorkspace} in a directory of its own, talking to the
 * daemon over a real socket through the real {@link DbGitClient}. This is what the {@code ./dbgit} wrapper in the
 * demo scripts is, so a test written against it reads as the script does.
 *
 * <p>It mirrors {@code org.example.client.Main}'s glue rather than calling it, for one reason: {@code Main} prints
 * to {@code System.out}, and the daemon under test shares this JVM and prints its own progress there too, so
 * capturing that stream would mix a {@code Forker}'s chatter into the command's output. {@link DbGitClient} takes
 * its streams as arguments, so passing them in keeps each command's output exactly its own. The glue being
 * mirrored is the part that matters to a test: the workspace's {@code HEAD} and its {@code config.json} move only
 * once the daemon has accepted the command.
 */
public final class DbGitCli {
    private final ClientWorkspace workspace;
    private final DbGitClient client;

    public DbGitCli(Path directory, int port) {
        this.workspace = new ClientWorkspace(directory);
        this.client = new DbGitClient(port);
    }

    /** The branch this workspace is on, straight out of its own {@code .dbgit/HEAD}. */
    public String branch() {
        return workspace.currentBranch();
    }

    /** Another user of the same daemon, with a workspace - and so a checked-out branch - of their own. */
    public DbGitCli otherWorkspace(Path directory, int port) {
        return new DbGitCli(directory, port);
    }

    public CommandOutput run(String... arguments) {
        List<String> command = List.of(arguments);
        String verb = command.isEmpty() ? "" : command.getFirst();
        return switch (verb) {
            case "init" -> init(command);
            case "checkout" -> checkout(command);
            default -> send((out, err) -> client.run(workspace.requestContext(), command, out, err));
        };
    }

    /** {@code dbgit add}, whose DDL comes from stdin rather than the argument list and may span lines. */
    public CommandOutput add(String ddl) {
        return send((out, err) -> client.runAdd(workspace.requestContext(), ddl, out, err));
    }

    /** The connection flags are the client's to parse; the workspace records them only once the daemon agrees. */
    private CommandOutput init(List<String> arguments) {
        List<String> rest = new ArrayList<>(arguments.subList(1, arguments.size()));
        String author = ConnectionArguments.extractAuthor(rest);
        ConnectionSettings settings = ConnectionArguments.parse(rest);
        RequestContext request = new RequestContext(author, workspace.currentBranch(), settings);
        CommandOutput output = send((out, err) -> client.run(request, List.of("init"), out, err));
        if (output.succeeded()) {
            workspace.track(RequestContext.DEFAULT_BRANCH, settings);
            workspace.trackAuthor(author);
        }
        return output;
    }

    /** The one command whose effect is local: HEAD moves only once the daemon has accepted the checkout. */
    private CommandOutput checkout(List<String> arguments) {
        CommandOutput output = send((out, err) -> client.run(workspace.requestContext(), arguments, out, err));
        if (output.succeeded()) {
            branchName(arguments).ifPresent(workspace::checkout);
        }
        return output;
    }

    private static Optional<String> branchName(List<String> arguments) {
        if (arguments.size() == 2) {
            return Optional.of(arguments.get(1));
        }
        if (arguments.size() == 3 && arguments.get(1).equals("-b")) {
            return Optional.of(arguments.get(2));
        }
        return Optional.empty();
    }

    private static CommandOutput send(BiFunction<PrintStream, PrintStream, Integer> call) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode;
        try (PrintStream outStream = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            exitCode = call.apply(outStream, errStream);
        }
        return new CommandOutput(exitCode, lines(out), lines(err));
    }

    private static List<String> lines(ByteArrayOutputStream captured) {
        String text = captured.toString(StandardCharsets.UTF_8);
        return text.isEmpty() ? List.of() : List.of(text.stripTrailing().split("\\R"));
    }
}
