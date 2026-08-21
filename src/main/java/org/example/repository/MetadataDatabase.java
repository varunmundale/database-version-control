package org.example.repository;

import org.example.config.ConnectionSettings;
import org.example.config.MetadataStoreConfig;
import org.example.connectors.JdbcConnections;
import org.example.connectors.postgres.PostgresConnections;
import org.jooq.ConnectionProvider;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.ThreadLocalTransactionProvider;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.function.Supplier;

/**
 * The metadata store's single point of contact with its PostgreSQL server: creates the database and its tables once,
 * on first use, then hands every repository in this package a {@link DSLContext} to run against.
 *
 * <p>The context is configured with a {@link ThreadLocalTransactionProvider}, so repository calls made inside
 * {@link #transaction} automatically join that transaction without any of them having to take a connection or
 * context as an argument - which is what lets the repositories be stateless singletons.
 */
public final class MetadataDatabase {
    /** The metadata store is PostgreSQL by definition - the jOOQ dialect below is fixed to match. */
    private static final JdbcConnections POSTGRES = PostgresConnections.INSTANCE;

    private final DSLContext dsl;

    private MetadataDatabase(MetadataStoreConfig config) {
        createDatabaseIfMissing(config);
        ConnectionProvider connections = new BorrowedConnections(POSTGRES, config.connectionTo(config.database()));
        this.dsl = DSL.using(new DefaultConfiguration()
                .set(connections)
                .set(new ThreadLocalTransactionProvider(connections, true))
                .set(SQLDialect.POSTGRES));
        MetadataSchema.ensure(dsl);
    }

    /** Connects and bootstraps on first call; every later call reuses the same context. */
    public static MetadataDatabase getInstance() {
        return Holder.INSTANCE;
    }

    /** Runs a read or a single statement, translating jOOQ's failures into a {@link RepositoryException}. */
    public <T> T query(Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException exception) {
            throw new RepositoryException("Could not access branch metadata: " + exception.getMessage(), exception);
        }
    }

    /** {@link #query} for work that returns nothing. */
    public void execute(Runnable work) {
        query(() -> {
            work.run();
            return null;
        });
    }

    /** Runs {@code work} - and every repository call it makes - in one transaction, rolled back if it throws. */
    public <T> T transaction(String failureMessage, Supplier<T> work) {
        try {
            return dsl.transactionResult(work::get);
        } catch (DataAccessException exception) {
            throw new RepositoryException(failureMessage + ": " + exception.getMessage(), exception);
        }
    }

    /** Visible to this package's repositories only: the outside world goes through the versioning service. */
    DSLContext dsl() {
        return dsl;
    }

    private static void createDatabaseIfMissing(MetadataStoreConfig config) {
        try (Connection connection = POSTGRES.open(config.connectionTo(config.adminDatabase()));
             PreparedStatement exists = connection.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
            exists.setString(1, config.database());
            try (ResultSet resultSet = exists.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
            try (Statement create = connection.createStatement()) {
                create.execute("CREATE DATABASE \"" + config.database() + "\"");
            }
        } catch (SQLException exception) {
            throw new RepositoryException("Could not create metadata database '" + config.database() + "': "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * Lets jOOQ borrow one short-lived connection per unit of work, opened by the connectors package like every
     * other connection dbgit makes, and handed back as soon as the work is done.
     */
    private record BorrowedConnections(JdbcConnections connections, ConnectionSettings settings) implements ConnectionProvider {
        @Override
        public Connection acquire() {
            try {
                return connections.open(settings);
            } catch (SQLException exception) {
                throw new DataAccessException("Could not connect to metadata database '" + settings.database() + "'", exception);
            }
        }

        @Override
        public void release(Connection connection) {
            try {
                connection.close();
            } catch (SQLException exception) {
                throw new DataAccessException("Could not close metadata database connection", exception);
            }
        }
    }

    /** Defers the connection attempt until something actually reads or writes metadata. */
    private static final class Holder {
        private static final MetadataDatabase INSTANCE = new MetadataDatabase(MetadataStoreConfig.getInstance());
    }
}
