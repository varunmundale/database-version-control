package org.example.dbgit;

import java.io.IOException;
import java.nio.file.Path;

/** Standalone entry point for the {@code dbService} daemon: stays running and serves {@code dbgit} clients. */
public final class DbServiceMain {
    public static void main(String[] args) throws IOException {
        Path workingDirectory = Path.of(".").toAbsolutePath().normalize();
        int port = ServiceConfig.getInstance().port();
        try (DbGitServer server = new DbGitServer(workingDirectory, port)) {
            System.out.println("dbService listening on port " + server.port() + " (workspace: " + workingDirectory + ")");
            server.serve();
        }
    }
}
