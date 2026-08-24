package org.example.service;

import org.example.core.forker.BranchConnections;
import org.example.config.BranchDatabaseConfig;
import org.example.config.ConcurrencyConfig;
import org.example.core.forker.Forker;
import org.example.core.locking.AdvisoryBranchLock;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.request.RequestContext;
import org.example.service.command.AddCommand;
import org.example.service.command.Command;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The {@code dbService} daemon: accepts TCP connections and hands each to a bounded thread pool as a
 * {@link ConnectionHandler}, so one caller's {@code checkout -b} doesn't block everyone else behind a Docker pull.
 * The pool is bounded rather than elastic because each command holds real database connections for its duration;
 * a full pool answers "busy" instead of queueing without limit - size it for the slowest command, not the average.
 *
 * <p>{@link #execute} and {@link #add} are the same entry points the socket path uses, callable directly - which
 * is how the command tests drive every {@code dbgit} verb without a client.
 */
public final class DbGitCommandListener implements Closeable {
    private final CommandContext context;
    private final CommandFactory commandFactory;
    private final ServerSocket serverSocket;
    private final ConcurrencyConfig concurrency;
    private final ThreadPoolExecutor handlers;

    public DbGitCommandListener(int port) throws IOException {
        this(new Forker(), port);
    }

    public DbGitCommandListener(Forker forker, int port) throws IOException {
        this(forker, port, ConcurrencyConfig.getInstance(), advisoryLocks(ConcurrencyConfig.getInstance()));
    }

    /** Advisory locking is the default because it holds across daemon processes; tests inject an in-memory one. */
    private static BranchLocks advisoryLocks(ConcurrencyConfig concurrency) {
        return new BranchLocks(new AdvisoryBranchLock(), Duration.ofMillis(concurrency.lockTimeoutMs()));
    }

    /** A bind failure here is almost always a second {@code dbService} already running on this port. */
    private static ServerSocket bind(int port) throws IOException {
        try {
            return new ServerSocket(port);
        } catch (BindException exception) {
            throw new BindException("Port " + port + " is already in use - is another dbService already running? "
                    + "Check with 'lsof -i :" + port + "' or 'ss -tlnp | grep " + port
                    + "', and stop it before starting this one.");
        }
    }

    public DbGitCommandListener(Forker forker, int port, ConcurrencyConfig concurrency, BranchLocks locks)
            throws IOException {
        this.context = new CommandContext(Objects.requireNonNull(forker, "forker must not be null"),
                new Replayer(), new BranchConnections(BranchDatabaseConfig.getInstance()),
                Objects.requireNonNull(locks, "locks must not be null"),
                new RequestContext(null, null, null));
        this.commandFactory = new CommandFactory(context);
        this.concurrency = Objects.requireNonNull(concurrency, "concurrency must not be null");
        this.serverSocket = bind(port);
        this.handlers = new ThreadPoolExecutor(concurrency.handlerThreads(), concurrency.handlerThreads(),
                0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(concurrency.queueDepth()),
                DbGitCommandListener::handlerThread, new RespondBusy());
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    /** The accept thread does no work beyond handing off to the pool, so a slow command never blocks new clients. */
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
            handlers.execute(new ConnectionHandler(socket, commandFactory, context, concurrency));
        }
    }

    /** Drains rather than kills: a {@code reset} interrupted between its {@code DROP DATABASE} and replay would
     *  leave a branch with no database at all. */
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

    private static Thread handlerThread(Runnable task) {
        Thread thread = new Thread(task, "dbgit-handler");
        thread.setDaemon(true);
        return thread;
    }

    /** A refused client can retry immediately; one silently queued behind a Docker pull would just time out. */
    private static final class RespondBusy implements RejectedExecutionHandler {
        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            ((ConnectionHandler) task).reject();
        }
    }
}
