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
import org.jooq.tools.jdbc.JDBCUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * The metadata store: the standalone PostgreSQL server dbgit's own bookkeeping lives in. Creates the database and
 * applies {@code metadata-schema.sql} once, on first use, then hands every repository in this package a
 * {@link DSLContext} bound to a {@link ThreadLocalTransactionProvider}, so a repository call inside
 * {@link #transaction} joins it automatically without taking a connection argument.
 */
public final class MetadataDatabase {
    private static final String SCHEMA_FILE = "metadata-schema.sql";

    /** The metadata store is PostgreSQL by definition - the jOOQ dialect below is fixed to match. */
    private static final JdbcConnections POSTGRES = PostgresConnections.INSTANCE;

    private final DSLContext dsl;

    private MetadataDatabase(MetadataStoreConfig config) {
        createDatabaseIfMissing(config);
        ConnectionProvider connections = new BorrowedConnections(config.connectionTo(config.database()));
        this.dsl = DSL.using(new DefaultConfiguration()
                .set(connections)
                .set(new ThreadLocalTransactionProvider(connections, true))
                .set(SQLDialect.POSTGRES));
        applySchema(dsl);
    }

    /** The metadata store described by {@code dbgit.json}; connects and bootstraps on first call. */
    public static MetadataDatabase getInstance() {
        return Holder.INSTANCE;
    }

    /** Runs a read or a single statement, translating jOOQ's failures into a {@link RepositoryException}. */
    public <T> T query(Supplier<T> work) {
        return guard("Could not access branch metadata", work);
    }

    /** {@link #query} for work that returns nothing. */
    public void execute(Runnable work) {
        query(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Runs a read in one {@code REPEATABLE READ} transaction, so a multi-query read sees one consistent snapshot
     * instead of jOOQ's default of a fresh connection (and snapshot) per query.
     */
    public <T> T snapshot(String failureMessage, Supplier<T> work) {
        return transaction(failureMessage, () -> {
            dsl.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ");
            return work.get();
        });
    }

    /** Runs {@code work} - and every repository call it makes - in one transaction, rolled back if it throws. */
    public <T> T transaction(String failureMessage, Supplier<T> work) {
        return guard(failureMessage, () -> dsl.transactionResult(work::get));
    }

    /** Visible to this package's repositories only: the outside world goes through the versioning service. */
    DSLContext dsl() {
        return dsl;
    }

    private static <T> T guard(String failureMessage, Supplier<T> work) {
        try {
            return work.get();
        } catch (DataAccessException exception) {
            throw new RepositoryException(failureMessage + ": " + exception.getMessage(), exception);
        }
    }

    /** The one thing that cannot be done from inside the metadata database: bring it into existence. */
    private static void createDatabaseIfMissing(MetadataStoreConfig config) {
        try (Connection admin = POSTGRES.open(config.connectionTo(config.adminDatabase()))) {
            DSLContext ctx = DSL.using(admin, SQLDialect.POSTGRES);
            if (!ctx.fetchExists(DSL.table("pg_database"), DSL.field("datname", String.class).eq(config.database()))) {
                ctx.execute("CREATE DATABASE {0}", DSL.name(config.database()));
            }
        } catch (SQLException | DataAccessException exception) {
            throw new RepositoryException("Could not create metadata database '" + config.database() + "': "
                    + exception.getMessage(), exception);
        }
    }

    /**
     * Applies {@code metadata-schema.sql} unconditionally; every statement in it is idempotent. Comments are
     * stripped before splitting on {@code ;}, since a semicolon inside a comment's prose would otherwise cut a
     * statement in half.
     */
    private static void applySchema(DSLContext ctx) {
        try (InputStream input = MetadataDatabase.class.getClassLoader().getResourceAsStream(SCHEMA_FILE)) {
            if (input == null) {
                throw new RepositoryException("Missing " + SCHEMA_FILE + " on the classpath");
            }
            String sql = withoutComments(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            Arrays.stream(sql.split(";")).map(String::strip).filter(statement -> !statement.isBlank())
                    .forEach(ctx::execute);
        } catch (IOException exception) {
            throw new RepositoryException("Could not read " + SCHEMA_FILE + ": " + exception.getMessage(), exception);
        }
    }

    /** Everything from a {@code --} to the end of its line, removed - see {@link #applySchema}. */
    private static String withoutComments(String sql) {
        return sql.lines()
                .map(line -> line.indexOf("--") < 0 ? line : line.substring(0, line.indexOf("--")))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Lets jOOQ borrow one short-lived connection per unit of work, opened by the connectors package like every
     * other connection dbgit makes, and handed back as soon as the work is done.
     */
    private record BorrowedConnections(ConnectionSettings settings) implements ConnectionProvider {
        @Override
        public Connection acquire() {
            try {
                return POSTGRES.open(settings);
            } catch (SQLException exception) {
                throw new DataAccessException("Could not connect to metadata database '" + settings.database() + "'", exception);
            }
        }

        @Override
        public void release(Connection connection) {
            JDBCUtils.safeClose(connection);
        }
    }

    /** Defers the connection attempt until something actually reads or writes metadata. */
    private static final class Holder {
        private static final MetadataDatabase INSTANCE = new MetadataDatabase(MetadataStoreConfig.getInstance());
    }
}
