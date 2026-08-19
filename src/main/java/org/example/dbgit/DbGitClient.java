package org.example.dbgit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.ConnectException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CLI-facing client for the {@code dbgit} command; talks to an already-running {@code dbService} daemon. */
public final class DbGitClient {
    private final int port;

    public DbGitClient() {
        this(ServiceConfig.getInstance().port());
    }

    public DbGitClient(int port) {
        this.port = port;
    }

    public int run(List<String> args, PrintStream out, PrintStream err) {
        String commandLine = "dbgit " + String.join(" ", args);
        try (Socket socket = new Socket("localhost", port)) {
            try (PrintWriter writer = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
                writer.println(commandLine);
                socket.shutdownOutput();

                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                String line;
                boolean ok = "OK".equals(status);
                PrintStream destination = ok ? out : err;
                while ((line = reader.readLine()) != null) {
                    destination.println(line);
                }
                return ok ? 0 : 1;
            }
        } catch (ConnectException exception) {
            err.println("dbService is not running. Start it first, e.g.: ./dbService");
            return 1;
        } catch (IOException exception) {
            err.println("Could not communicate with dbService: " + exception.getMessage());
            return 1;
        }
    }
}
