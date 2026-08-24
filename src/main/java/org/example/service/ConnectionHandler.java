package org.example.service;

import org.example.config.ConcurrencyConfig;
import org.example.request.RequestContext;
import org.example.request.RequestHeader;
import org.example.service.command.AddCommand;
import org.example.service.command.CommandContext;
import org.example.service.command.CommandFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.util.Arrays;

/**
 * Frames one accepted connection into a request and dispatches it: a {@link RequestHeader} line, then the command
 * line, then - for {@code dbgit add} alone - a body. Owns the socket for its whole life. Logging deliberately stops
 * at the command line: the header carries {@code db-password}, so that never gets a second copy in the log.
 */
final class ConnectionHandler implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(ConnectionHandler.class);
    private static final String ADD_COMMAND = "dbgit add";

    private final Socket socket;
    private final CommandFactory commandFactory;
    private final CommandContext context;
    private final ConcurrencyConfig concurrency;

    ConnectionHandler(Socket socket, CommandFactory commandFactory, CommandContext context,
            ConcurrencyConfig concurrency) {
        this.socket = socket;
        this.commandFactory = commandFactory;
        this.context = context;
        this.concurrency = concurrency;
    }

    @Override
    public void run() {
        try (Socket owned = socket) {
            handle(owned);
        } catch (IOException exception) {
            LOG.warn("Dropped a connection: {}", exception.getMessage());
        }
    }

    /** Called instead of {@link #run()} when the pool's queue is full; the socket still needs an answer. */
    void reject() {
        LOG.warn("Server busy: all {} handlers are in use and the queue is full; rejecting a connection.",
                concurrency.handlerThreads());
        try (Socket owned = socket;
             SocketWriter writer = new SocketWriter(owned)) {
            writer.writeError("Server busy: all " + concurrency.handlerThreads()
                    + " handlers are in use and the queue is full. Try again shortly.");
        } catch (IOException ignored) {
            // The client has gone; nothing left to tell it.
        }
    }

    /** The header is mandatory: the branch and the tracked database's credentials both arrive on it. */
    private void handle(Socket socket) throws IOException {
        // Without this a client that never speaks (or never closes its `dbgit add` body) holds a pool thread forever.
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
                LOG.warn("Rejected a request with an unsupported header: {}", exception.getMessage());
                writer.writeError(exception.getMessage());
                return;
            }

            String commandLine = reader.nextLine();
            if (commandLine == null || commandLine.isBlank()) {
                writer.writeError("No command received.");
                return;
            }
            LOG.info("author={} branch={} command=\"{}\"", request.author(), request.branch(), commandLine.trim());
            try {
                writer.writeOk(dispatch(request, commandLine, reader).lines());
            } catch (RuntimeException exception) {
                LOG.warn("author={} branch={} command=\"{}\" failed: {}",
                        request.author(), request.branch(), commandLine.trim(), exception.getMessage());
                writer.writeError(exception.getMessage());
            }
        }
    }

    /** Only {@code dbgit add} carries a body, since its DDL can span lines. */
    private DbGitCommandResult dispatch(RequestContext request, String commandLine, SocketReader reader)
            throws IOException {
        if (commandLine.trim().equals(ADD_COMMAND)) {
            return new AddCommand(context.forRequest(request), reader.remaining()).execute();
        }
        return commandFactory.create(request, Arrays.stream(commandLine.trim().split("\\s+")).toList()).execute();
    }
}
