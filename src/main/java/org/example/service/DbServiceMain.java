package org.example.service;

import org.example.config.ServiceEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Standalone entry point for the {@code dbService} daemon: stays running and serves {@code dbgit} clients.
 *
 * <p>Deliberately captures no working directory. The daemon holds no per-user state - which branch a caller is on,
 * and how to reach the database {@code main} tracks, both arrive on each request - so it serves every workspace
 * equally rather than the one it happened to be started in.
 */
public final class DbServiceMain {
    private static final Logger LOG = LoggerFactory.getLogger(DbServiceMain.class);

    public static void main(String[] args) throws IOException {
        int port = ServiceEndpointConfig.getInstance().port();
        DbGitCommandListener listener;
        try {
            listener = new DbGitCommandListener(port);
        } catch (IOException exception) {
            // Logged here, not left to whatever prints the propagating exception: 'mvn exec:java' wraps it in its
            // own MojoExecutionException noise, and a systemd unit with Restart=on-failure would otherwise hide it
            // in a crash loop instead of saying once, clearly, why this attempt failed.
            LOG.error("Could not start dbService: {}", exception.getMessage());
            throw exception;
        }
        try (listener) {
            LOG.info("dbService listening on port {}", listener.port());
            listener.serve();
        } finally {
            LOG.info("dbService stopped.");
        }
    }
}
