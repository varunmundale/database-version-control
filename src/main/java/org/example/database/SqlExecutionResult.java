package org.example.database;

import java.util.List;
import java.util.Map;

/** Result of executing one SQL statement. */
public record SqlExecutionResult(boolean hasResultSet, int updateCount, List<Map<String, Object>> rows) {
    public SqlExecutionResult {
        rows = rows.stream().map(Map::copyOf).toList();
    }

    static SqlExecutionResult query(List<Map<String, Object>> rows) {
        return new SqlExecutionResult(true, -1, rows);
    }

    static SqlExecutionResult update(int updateCount) {
        return new SqlExecutionResult(false, updateCount, List.of());
    }
}
