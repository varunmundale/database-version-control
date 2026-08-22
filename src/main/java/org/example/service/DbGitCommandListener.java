package org.example.service;

import org.example.core.forker.BranchConnections;
import org.example.config.BranchDatabaseConfig;
import org.example.config.ConcurrencyConfig;
import org.example.core.forker.Forker;
import org.example.core.replayer.Replayer;
import org.example.protocol.RequestContext;
import org.example.protocol.RequestHeader;
import org.example.service.command.AddCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The {@code dbService} daemon: listens on a local TCP socket and runs each incoming command line. Owns the accept
 * loop and the shape of a request - a command line, optionally followed by a body - while the byte-level work of
 * reading and writing belongs to {@link SocketReader} and {@link SocketWriter}, and picking the {@link Command} for
 * a given argument list belongs to {@link CommandFactory}.
 *
 * <p>{@link #execute} and {@link #add} are the same entry points the socket path uses, callable directly by anyone
 * embedding the daemon - which is how the command tests drive every {@code dbgit} verb without a client.
 *
 * <p>Connections are handed to a bounded pool, so one caller's {@code checkout -b} no longer blocks everyone
 * else's commands behind a Docker pull. The pool is bounded rather than elastic because each command holds real
 * database connections for its whole duration; when it and its queue are full the daemon says so, which is a
 * better answer than an unbounded backlog of clients that have already timed out. Size it for the slowest command,
 * not the average one.
 */
public final class DbGitCommandListener implements Closeable {
    private static final String ADD_COMMAND = "dbgit add";

    private final CommandContext context;
    private final CommandFactory commandFactory;
    private final ServerSocket serverSocket;
    private final ConcurrencyConfig concurrency;
    private final ThreadPoolExecutor handlers;

    public DbGitCommandListener(int port) throws IOException {
        this(new Forker(), port);
    }

    public DbGitCommandListener(Forker forker, int port) throws IOException {
        this(forker, port, ConcurrencyConfig.getInstance());
    }

    public DbGitCommandListener(Forker forker, int port, ConcurrencyConfig concurrency) throws IOException {
        this.context = new CommandContext(Objects.requireNonNull(forker, "forker must not be null"),
                new Replayer(), new BranchConnections(BranchDatabaseConfig.getInstance()),
                new RequestContext(null, null, null));
        this.commandFactory = new CommandFactory(context);
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency must not be null");
        this.serverSocket = new ServerSocket(port);
        this.handlers = new ThreadPoolExecutor(concurrency.handlerThreads(), concurrency.handlerThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency.queueDepth()),
                DbGitCommandListener::handlerThread, new RespondBusy());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /**
     * Blocks, accepting connections and handing each to the pool, until {@link #close()} is called. The accept
     * thread does no work of its own beyond that, so a slow command never stops new clients being accepted.
     */
    public void serve() throws IOException {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (SocketException exception) {
                if (serverSocket.isClosed()) {
                    return;
                }
                throw exception;
            }
            // The task owns the socket from here, including closing it - accepting must not outrun handling.
            handlers.execute(new Handler(socket));
        }
    }

    /**
     * Stops accepting, then gives in-flight commands time to finish rather than killing them: a {@code reset}
     * interrupted between its {@code DROP DATABASE} and its replay would leave a branch with no database at all.
     */
    @Override
    public void close() throws IOException {
        serverSocket.close();
        handlers.shutdown();
        try {
            if (!handlers.awaitTermination(concurrency.drainTimeoutMs(), TimeUnit.MILLISECONDS)) {
                handlers.shutdownNow();
            }
        } catch (InterruptedException exception) {
            handlers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public DbGitCommandResult execute(RequestContext request, String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(request, Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(RequestContext request, List<String> arguments) {
        return commandFactory.create(request, arguments).execute();
    }

    /** {@code dbgit add} arrives separately because its DDL body comes from stdin rather than the argument list. */
    public DbGitCommandResult add(RequestContext request, String ddl) {
        return new AddCommand(context.forRequest(request), ddl).execute();
    }

    /**
     * A request is a {@link RequestHeader} line, then the command, then - for {@code dbgit add} alone - a body.
     * The header is mandatory: the branch and the tracked database's credentials both arrive on it, so a request
     * without one carries too little to act on.
     */
    private void handle(Socket socket) throws IOException {
        // Without this a client that opens a connection and never speaks - or never closes its `dbgit add` body -
        // holds one of the pool's threads indefinitely.
        socket.setSoTimeout(concurrency.socketTimeoutMs());
        try (SocketReader reader = new SocketReader(socket);
             SocketWriter writer = new SocketWriter(socket)) {
            String headerLine = reader.nextLine();
            if (headerLine == null || headerLine.isBlank()) {
                writer.writeError("No command received.");
                return;
            }
            RequestContext request;
            try {
                request = RequestHeader.parse(headerLine);
            } catch (IllegalArgumentException exception) {
                writer.writeError(exception.getMessage());
                return;
            }

            String commandLine = reader.nextLine();
            if (commandLine == null || commandLine.isBlank()) {
                writer.writeError("No command received.");
                return;
            }
            try {
                writer.writeOk(run(request, commandLine, reader).lines());
            } catch (RuntimeException exception) {
                writer.writeError(exception.getMessage());
            }
        }
    }

    /**
     * Only {@code dbgit add} carries a body - its DDL can span lines, so it arrives after the command line rather
     * than inside it - so only that branch asks the reader for what is left.
     */
    private DbGitCommandResult run(RequestContext request, String commandLine, SocketReader reader) throws IOException {
        if (commandLine.trim().equals(ADD_COMMAND)) {
            return add(request, reader.remaining());
        }
        return execute(request, commandLine);
    }

    private static Thread handlerThread(Runnable task) {
        Thread thread = new Thread(task, "dbgit-handler");
        thread.setDaemon(true);
        return thread;
    }

    /** Owns one connection for its whole life, closing it however the command turns out. */
    private final class Handler implements Runnable {
        private final Socket socket;

        private Handler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            try (Socket owned = socket) {
                handle(owned);
            } catch (IOException exception) {
                System.err.println("[dbService] Dropped a connection: " + exception.getMessage());
            }
        }

        void reject() {
            try (Socket owned = socket;
                 SocketWriter writer = new SocketWriter(owned)) {
                writer.writeError("Server busy: all " + concurrency.handlerThreads()
                        + " handlers are in use and the queue is full. Try again shortly.");
            } catch (IOException ignored) {
                // The client has gone; nothing left to tell it.
            }
        }
    }

    /**
     * Overflow gets an answer rather than a dropped connection or an unbounded backlog - a client that is told it
     * was refused can retry, while one queued behind a Docker pull just times out.
     */
    private static final class RespondBusy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            ((Handler) task).reject();
        }
    }
}
