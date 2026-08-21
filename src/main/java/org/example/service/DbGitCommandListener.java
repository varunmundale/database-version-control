package org.example.service;

import org.example.core.forker.Forker;
import org.example.core.replayer.Replayer;
import org.example.repository.DbGitLocalRepository;
import org.example.service.command.AddCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The {@code dbService} daemon: listens on a local TCP socket and runs each incoming command line. Owns the accept
 * loop and the shape of a request - a command line, optionally followed by a body - while the byte-level work of
 * reading and writing belongs to {@link SocketReader} and {@link SocketWriter}, and picking the {@link Command} for
 * a given argument list belongs to {@link CommandFactory}.
 *
 * <p>{@link #execute} and {@link #add} are the same entry points the socket path uses, callable directly by anyone
 * embedding the daemon - which is how the command tests drive every {@code dbgit} verb without a client.
 */
public final class DbGitCommandListener implements Closeable {
    private static final String ADD_COMMAND = "dbgit add";

    private final CommandContext context;
    private final CommandFactory commandFactory;
    private final ServerSocket serverSocket;

    public DbGitCommandListener(Path workingDirectory, int port) throws IOException {
        this(workingDirectory, new Forker(), port);
    }

    public DbGitCommandListener(Path workingDirectory, Forker forker, int port) throws IOException {
        DbGitLocalRepository repository = new DbGitLocalRepository(
                Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.context = new CommandContext(repository, Objects.requireNonNull(forker, "forker must not be null"), new Replayer());
        this.commandFactory = new CommandFactory(context);
        this.serverSocket = new ServerSocket(port);
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** Blocks, accepting and handling one connection at a time, until {@link #close()} is called. */
    public void serve() throws IOException {
        while (!serverSocket.isClosed()) {
            try (Socket socket = serverSocket.accept()) {
                handle(socket);
            } catch (SocketException exception) {
                if (serverSocket.isClosed()) {
                    return;
                }
                throw exception;
            }
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        return commandFactory.create(arguments).execute();
    }

    /** {@code dbgit add} arrives separately because its DDL body comes from stdin rather than the argument list. */
    public DbGitCommandResult add(String ddl) {
        return new AddCommand(context, ddl).execute();
    }

    private void handle(Socket socket) throws IOException {
        try (SocketReader reader = new SocketReader(socket);
             SocketWriter writer = new SocketWriter(socket)) {
            String commandLine = reader.nextLine();
            if (commandLine == null || commandLine.isBlank()) {
                writer.writeError("No command received.");
                return;
            }
            try {
                writer.writeOk(run(commandLine, reader).lines());
            } catch (RuntimeException exception) {
                writer.writeError(exception.getMessage());
            }
        }
    }

    /**
     * Only {@code dbgit add} carries a body - its DDL can span lines, so it arrives after the command line rather
     * than inside it - so only that branch asks the reader for what is left.
     */
    private DbGitCommandResult run(String commandLine, SocketReader reader) throws IOException {
        if (commandLine.trim().equals(ADD_COMMAND)) {
            return add(reader.remaining());
        }
        return execute(commandLine);
    }
}
