package org.example.connectors;

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

/** Shared JDBC implementation for the dialect-specific connectors. */
public abstract class JdbcConnector implements SqlConnector {
    private final Connection connection;

    protected JdbcConnector(String jdbcUrl) throws SQLException {
        connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"));
    }

    @Override
    public SqlExecutionResult execute(String sql) throws SQLException {
        Objects.requireNonNull(sql, "sql must not be null");
        try (Statement statement = connection.createStatement()) {
            if (!statement.execute(sql)) {
                return SqlExecutionResult.update(statement.getUpdateCount());
            }
            try (ResultSet resultSet = statement.getResultSet()) {
                return SqlExecutionResult.query(readRows(resultSet));
            }
        }
    }

    @Override
    public <T> T transaction(SqlTransaction<T> work) throws SQLException {
        Objects.requireNonNull(work, "work must not be null");
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            T result = work.execute(this);
            connection.commit();
            return result;
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
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
