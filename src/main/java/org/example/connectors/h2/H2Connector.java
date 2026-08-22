package org.example.connectors.h2;

import org.example.connectors.JdbcConnections;
import org.example.connectors.JdbcConnector;
import org.example.connectors.SqlExecutionResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small JDBC connector for executing SQL against an H2 database. Opened via {@link H2Connections}.
 *
 * <p>H2 has no {@code CREATE DATABASE}/{@code DROP DATABASE} statement of its own: an in-memory database springs
 * into existence the moment something first connects to it, and cannot be dropped as such. Both statements are
 * what {@link org.example.repository.BranchDatabaseRepository} forks and resets a branch with, and both actually
 * mean the same thing here - <em>leave the named database empty</em> - so both are translated, by opening a
 * connection to the database named in the statement (not this connector's own) and running
 * {@code DROP ALL OBJECTS} on it, rather than being sent to H2 as-is, which it would reject.
 */
public final class H2Connector extends JdbcConnector {
    private static final Pattern DATABASE_STATEMENT =
            Pattern.compile("^\\s*(?:CREATE|DROP)\\s+DATABASE\\s+(?:IF\\s+EXISTS\\s+)?\"?([^\"\\s;]+)\"?\\s*;?\\s*$",
                    Pattern.CASE_INSENSITIVE);

    public H2Connector(Connection connection) {
        super(connection);
    }

    public static H2Connector inMemory(String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        return new H2Connector(JdbcConnections.openUrl(H2Connections.inMemoryUrl(databaseName)));
    }

    @Override
    public SqlExecutionResult execute(String sql) throws SQLException {
        Matcher databaseStatement = DATABASE_STATEMENT.matcher(sql);
        if (databaseStatement.matches()) {
            empty(databaseStatement.group(1));
            return new SqlExecutionResult(false, 0, List.of());
        }
        return super.execute(sql);
    }

    /** Empties the named database - the property {@code CREATE}/{@code DROP DATABASE} actually depend on. */
    private static void empty(String database) throws SQLException {
        try (Connection connection = JdbcConnections.openUrl(H2Connections.inMemoryUrl(database));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }
}
