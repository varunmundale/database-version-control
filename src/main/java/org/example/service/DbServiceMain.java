package org.example.service;

import org.example.config.ServiceEndpointConfig;

import java.io.IOException;

/**
 * Standalone entry point for the {@code dbService} daemon: stays running and serves {@code dbgit} clients.
 *
 * <p>Deliberately captures no working directory. The daemon holds no per-user state - which branch a caller is on,
 * and how to reach the database {@code main} tracks, both arrive on each request - so it serves every workspace
 * equally rather than the one it happened to be started in.
 */
public final class DbServiceMain {
    public static void main(String[] args) throws IOException {
        int port = ServiceEndpointConfig.getInstance().port();
        try (DbGitCommandListener listener = new DbGitCommandListener(port)) {
            System.out.println("dbService listening on port " + listener.port());
            listener.serve();
        }
    }
}
