package org.example.service;

import org.example.config.ServiceEndpointConfig;

import java.io.IOException;
import java.nio.file.Path;

/** Standalone entry point for the {@code dbService} daemon: stays running and serves {@code dbgit} clients. */
public final class DbServiceMain {
    public static void main(String[] args) throws IOException {
        Path workingDirectory = Path.of(".").toAbsolutePath().normalize();
        int port = ServiceEndpointConfig.getInstance().port();
        try (DbGitCommandListener listener = new DbGitCommandListener(workingDirectory, port)) {
            System.out.println("dbService listening on port " + listener.port() + " (workspace: " + workingDirectory + ")");
            listener.serve();
        }
    }
}
