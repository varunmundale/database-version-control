package org.example.integration.support;

import org.example.config.ConnectionSettings;
import org.example.connectors.ConnectorFactory;
import org.example.connectors.JdbcConnections;
import org.example.connectors.SqlConnector;
import org.example.connectors.SqlExecutionResult;
import org.example.connectors.SqlTransaction;
import org.example.connectors.h2.H2Connector;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stands in for the shared PostgreSQL scratchpad container, and for the real database {@code main} tracks, with
 * in-memory H2 databases - one per {@link ConnectionSettings#database()}, addressed by name and living as long as
 * the JVM. Host, port and credentials are ignored, so {@code main}'s tracked connection and a forked branch's both
 * land here and stay distinct purely by database name.
 *
 * <p>Every DDL statement the demo scripts use runs on H2 unchanged, {@code SERIAL} and {@code TEXT} and
 * {@code ALTER COLUMN ... TYPE ... USING} included, which is what makes the swap worth doing: the tests exercise
 * the real {@code BranchDatabaseRepository}, the real replay, and real DDL against a real database engine, without
 * a container per branch.
 *
 * <p>Two statements have no H2 equivalent and are translated rather than passed through. {@code CREATE DATABASE}
 * and {@code DROP DATABASE} are how dbgit materializes and tears down a branch; an in-memory H2 database instead
 * springs into existence when it is first connected to and cannot be dropped at all. Both therefore mean the same
 * thing here - <em>leave this database empty</em> - which is what {@code DROP ALL OBJECTS} does. That keeps the
 * important property intact: a freshly forked or freshly reset branch starts from nothing but its replayed history.
 *
 * <p>One genuine difference remains, and it is worth knowing when reading a failure: H2 commits DDL implicitly, so
 * a replay that fails part-way leaves what ran before it in place, where PostgreSQL would roll the whole replay
 * back.
 */
public final class H2Databases implements ConnectorFactory {
    private static final Pattern DATABASE_STATEMENT =
            Pattern.compile("^\\s*(?:CREATE|DROP)\\s+DATABASE\\s+(?:IF\\s+EXISTS\\s+)?\"?([^\"\\s;]+)\"?\\s*;?\\s*$",
                    Pattern.CASE_INSENSITIVE);

    /** Every database handed out so far, so {@link #reset} can empty them all between tests. */
    private final Set<String> known = ConcurrentHashMap.newKeySet();

    @Override
    public SqlConnector connect(ConnectionSettings settings) throws SQLException {
        String database = settings.database();
        return new Database(database, new H2Connector(open(database)));
    }

    /** A plain JDBC connection to one of these databases, for tests that want to look at what dbgit actually built. */
    public Connection open(String database) throws SQLException {
        known.add(database);
        return JdbcConnections.openUrl("jdbc:h2:mem:" + database + ";DB_CLOSE_DELAY=-1");
    }

    /**
     * Empties every database handed out so far. An in-memory H2 database outlives the test that created it, so
     * without this a second test forking the same branch name would replay its history onto the first test's
     * tables. The names are kept rather than forgotten, so a database emptied once stays covered.
     */
    public void reset() {
        for (String database : known) {
            empty(database);
        }
    }

    private void empty(String database) {
        try (Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not empty H2 database '" + database + "': "
                    + exception.getMessage(), exception);
        }
    }

    /** One H2 database, with {@code CREATE}/{@code DROP DATABASE} translated on the way through. */
    private final class Database implements SqlConnector {
        private final String database;
        private final SqlConnector delegate;

        private Database(String database, SqlConnector delegate) {
            this.database = database;
            this.delegate = delegate;
        }

        @Override
        public SqlExecutionResult execute(String sql) throws SQLException {
            Matcher databaseStatement = DATABASE_STATEMENT.matcher(sql);
            if (databaseStatement.matches()) {
                empty(databaseStatement.group(1));
                return new SqlExecutionResult(false, 0, List.of());
            }
            return delegate.execute(sql);
        }

        /**
         * The work is handed <em>this</em> connector rather than the delegate, so statements run inside a replay
         * are translated the same way as statements run on their own.
         */
        @Override
        public <T> T transaction(SqlTransaction<T> work) throws SQLException {
            return delegate.transaction(ignored -> work.execute(this));
        }

        @Override
        public void close() throws SQLException {
            delegate.close();
        }

        @Override
        public String toString() {
            return "h2:" + database;
        }
    }
}
