package org.example.server;

import org.example.service.DbGitCommandResult;
import org.example.service.DbGitService;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

/** Listens on a local TCP socket and runs each incoming command line through a {@link DbGitService}. */
public final class DbGitServer implements Closeable {
    private static final String ADD_COMMAND = "dbgit add";

    private final DbGitService dbGitService;
    private final ServerSocket serverSocket;

    public DbGitServer(Path workingDirectory, int port) throws IOException {
        this(new DbGitService(workingDirectory), port);
    }

    public DbGitServer(DbGitService dbGitService, int port) throws IOException {
        this.dbGitService = Objects.requireNonNull(dbGitService, "dbGitService must not be null");
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

    private void handle(Socket socket) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
            String commandLine = reader.readLine();
            if (commandLine == null || commandLine.isBlank()) {
                writer.println("ERR");
                writer.println("No command received.");
                return;
            }
            try {
                DbGitCommandResult result = commandLine.trim().equals(ADD_COMMAND)
                        ? dbGitService.add(readRemaining(reader))
                        : dbGitService.execute(commandLine);
                writer.println("OK");
                result.lines().forEach(writer::println);
            } catch (RuntimeException exception) {
                writer.println("ERR");
                writer.println(exception.getMessage());
            }
        }
    }

    /** Reads the multiline DDL body that follows a {@code dbgit add} header line, up to the client's EOF. */
    private static String readRemaining(BufferedReader reader) throws IOException {
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!body.isEmpty()) {
                body.append(System.lineSeparator());
            }
            body.append(line);
        }
        return body.toString();
    }
}
