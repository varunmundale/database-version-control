package org.example.integration.support;

import org.example.config.MetadataStoreConfig;
import org.example.connectors.postgres.PostgresConnections;
import org.example.repository.MetadataDatabase;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * The real metadata store the integration tests run against: a throwaway PostgreSQL container standing in for the
 * always-on server {@code dbgit.json}'s {@code metadata} section describes - PostgreSQL, not H2, because
 * {@code MetadataDatabase}/{@code AdvisoryBranchLock} require it regardless of what dialect branch databases use.
 * Binds the <em>fixed</em> port the test classpath's {@code dbgit.json} names, since {@link MetadataStoreConfig}
 * is a singleton read once before any container could start - one container for the whole JVM as a result.
 */
public final class MetadataStore {
    private static final MetadataStoreConfig CONFIG = MetadataStoreConfig.getInstance();
    private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

    /** Every table {@code metadata-schema.sql} creates, in an order the foreign keys tolerate being emptied in. */
    private static final String TRUNCATE = "TRUNCATE branch_changesets, tracked_databases, branch_metadata,"
            + " branch_commits RESTART IDENTITY CASCADE";

    private static PostgreSQLContainer container;

    private MetadataStore() {
    }

    /** Whether there is a Docker daemon to run the container on; without one the integration tests skip. */
    public static boolean isDockerAvailable() {
        return DockerClientFactory.instance().isDockerAvailable();
    }

    /**
     * Starts the container once per JVM and lets {@link MetadataDatabase} bootstrap itself against it - creating
     * {@code dbgit_metadata} and applying {@code metadata-schema.sql}, exactly as it does in production.
     */
    public static synchronized void start() {
        if (container != null) {
            return;
        }
        PostgreSQLContainer starting = new PostgreSQLContainer(IMAGE)
                .withDatabaseName(CONFIG.adminDatabase())
                .withUsername(CONFIG.user())
                .withPassword(CONFIG.password());
        starting.setPortBindings(List.of(CONFIG.port() + ":" + PostgreSQLContainer.POSTGRESQL_PORT));
        starting.start();
        container = starting;

        MetadataDatabase.getInstance();
    }

    /**
     * Empties the metadata store between tests and restarts its identity sequences, so a test can assert on
     * {@code commit #1} rather than on whatever number the tests before it left behind.
     */
    public static void reset() {
        try (Connection connection = PostgresConnections.INSTANCE.open(CONFIG.connectionTo(CONFIG.database()));
             Statement statement = connection.createStatement()) {
            statement.execute(TRUNCATE);
            statement.execute("INSERT INTO branch_metadata (branch_name, forked_from) VALUES ('main', NULL)");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not empty the metadata store: " + exception.getMessage(), exception);
        }
    }
}
