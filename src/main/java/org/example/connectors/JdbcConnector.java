package org.example.connectors;

import org.example.adapters.DatabaseSchema;
import org.example.adapters.SchemaParser;

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
    private final SchemaParser schemaParser;

    protected JdbcConnector(String jdbcUrl, SchemaParser schemaParser) throws SQLException {
        connection = DriverManager.getConnection(Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null"));
        this.schemaParser = Objects.requireNonNull(schemaParser, "schemaParser must not be null");
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
    public DatabaseSchema inspectSchema() throws SQLException {
        return schemaParser.parse(connection);
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
