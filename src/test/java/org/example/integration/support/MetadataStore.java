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
 * The real metadata store the integration tests run against: a throwaway PostgreSQL container, standing in for the
 * always-on server {@code dbgit.json}'s {@code metadata} section describes.
 *
 * <p>PostgreSQL rather than H2 because this half of dbgit is PostgreSQL by definition - {@code MetadataDatabase}
 * fixes jOOQ to the Postgres dialect, {@code metadata-schema.sql} is Postgres DDL down to {@code BIGSERIAL} and
 * {@code TIMESTAMPTZ}, and {@code AdvisoryBranchLock} serializes branches through {@code pg_try_advisory_lock}.
 * The branch databases are the half that can be swapped for H2 - see {@link H2Databases}.
 *
 * <p>The container binds a <em>fixed</em> host port, the one the test classpath's {@code dbgit.json} names.
 * {@link MetadataStoreConfig} is a static singleton read once, long before any container could start, so the
 * container has to follow the configuration rather than the other way round. That is also why there is one
 * container for the whole JVM: the singletons behind it - {@link MetadataDatabase} included - can only ever be
 * pointed at one server.
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
     * Empties the metadata store between tests, restoring the one row {@code metadata-schema.sql} seeds it with.
     *
     * <p>Restarting the identity sequences is the point as much as the emptying is: branches, commits and
     * changesets are numbered from 1 again, so a test can assert on {@code commit #1} rather than on whatever
     * number the tests before it happened to leave behind.
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
