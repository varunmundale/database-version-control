package org.example.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A small JDBC connector for executing SQL against an H2 database. */
public final class H2Connector implements AutoCloseable {
    private final Connection connection;

    public H2Connector(String jdbcUrl) throws SQLException {
        connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"));
    }

    public static H2Connector inMemory(String databaseName) throws SQLException {
        if (databaseName == null || databaseName.isBlank()) {
            throw new IllegalArgumentException("databaseName must not be blank");
        }
        return new H2Connector("jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    }

    /**
     * Executes one SQL statement. Query results are returned as ordered maps keyed by column label;
     * statements without a result set return their JDBC update count.
     */
    public SqlExecutionResult execute(String sql) throws SQLException {
        Objects.requireNonNull(sql, "sql must not be null");
        try (Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                return SqlExecutionResult.update(statement.getUpdateCount());
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return SqlExecutionResult.query(readRows(resultSet));
            }
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }

    private static List<Map<String, Object>> readRows(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<Map<String, Object>> rows = new ArrayList<>();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int column = 1; column <= metadata.getColumnCount(); column++) {
                row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
            }
            rows.add(row);
        }
        return rows;
    }
}
