package org.example.service;

import org.example.config.ServiceEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Entry point for the {@code dbService} daemon. Captures no working directory: the daemon holds no per-user
 * state, since branch and credentials arrive on each request instead.
 */
public final class DbServiceMain {
    private static final Logger LOG = LoggerFactory.getLogger(DbServiceMain.class);

    public static void main(String[] args) throws IOException {
        int port = ServiceEndpointConfig.getInstance().port();
        DbGitCommandListener listener;
        try {
            listener = new DbGitCommandListener(port);
        } catch (IOException exception) {
            // Logged here rather than left to 'mvn exec:java's noisier wrapper exception.
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
