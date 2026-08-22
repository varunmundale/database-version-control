package org.example.core.locking;

import org.example.config.ConnectionSettings;
import org.example.config.MetadataStoreConfig;
import org.example.connectors.JdbcConnections;
import org.example.connectors.postgres.PostgresConnections;
import org.example.repository.RepositoryException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * Serializes branches through the metadata store's own PostgreSQL advisory locks, so the guarantee holds across
 * daemon processes rather than only within one JVM.
 *
 * <p>Deliberately a <em>session</em> lock on a connection of its own, not {@code pg_advisory_xact_lock} inside the
 * metadata transaction: the operations being protected run irreversible side effects - live DDL, {@code CREATE}
 * and {@code DROP DATABASE}, {@code docker} - after their metadata transaction has committed, and a
 * transaction-scoped lock is gone by then. The cost is one connection held per in-flight mutating command, which
 * is why {@code pools.metadata.maxSize} must be at least twice the daemon's thread count.
 *
 * <p>The connection is closed when the lease is released, which also drops the lock - so a handler that dies
 * without unlocking still frees the branch as soon as its connection goes.
 */
public final class AdvisoryBranchLock implements BranchLock {
    /** Keeps dbgit's keys from colliding with any other application's advisory locks on the same server. */
    private static final int NAMESPACE = 0x0DB6_1701;
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private final JdbcConnections connections;
    private final ConnectionSettings settings;

    public AdvisoryBranchLock() {
        this(PostgresConnections.INSTANCE, MetadataStoreConfig.getInstance().connectionTo(
                MetadataStoreConfig.getInstance().database()));
    }

    public AdvisoryBranchLock(JdbcConnections connections, ConnectionSettings settings) {
        this.connections = Objects.requireNonNull(connections, "connections must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
    }

    /** A branch's lock key: stable across processes and restarts, since it is derived from the name alone. */
    public static int keyFor(String branch) {
        CRC32 digest = new CRC32();
        digest.update(branch.getBytes(StandardCharsets.UTF_8));
        return (int) digest.getValue();
    }

    @Override
    public BranchLease acquire(String branch, Duration timeout) {
        Objects.requireNonNull(branch, "branch must not be null");
        Connection connection = open(branch);
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (tryLock(connection, branch)) {
                    return () -> release(connection, branch);
                }
                if (System.nanoTime() >= deadline) {
                    throw new LockTimeoutException(branch, timeout);
                }
                Thread.sleep(POLL_INTERVAL.toMillis());
            }
        } catch (InterruptedException exception) {
            close(connection);
            Thread.currentThread().interrupt();
            throw new LockTimeoutException(branch, exception);
        } catch (RuntimeException exception) {
            close(connection);
            throw exception;
        }
    }

    private Connection open(String branch) {
        try {
            return connections.open(settings);
        } catch (SQLException exception) {
            throw new RepositoryException("Could not connect to the metadata store to lock branch '" + branch
                    + "': " + exception.getMessage(), exception);
        }
    }

    private static boolean tryLock(Connection connection, String branch) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, keyFor(branch));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        } catch (SQLException exception) {
            throw new RepositoryException("Could not lock branch '" + branch + "': " + exception.getMessage(), exception);
        }
    }

    private static void release(Connection connection, String branch) {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            statement.setInt(1, NAMESPACE);
            statement.setInt(2, keyFor(branch));
            statement.executeQuery().close();
        } catch (SQLException exception) {
            throw new RepositoryException("Could not unlock branch '" + branch + "': " + exception.getMessage(), exception);
        } finally {
            close(connection);
        }
    }

    private static void close(Connection connection) {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Dropping the connection releases the lock regardless; there is nothing useful to do here.
        }
    }
}
