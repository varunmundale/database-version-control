package org.example.database;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Result of executing one SQL statement. */
public record SqlExecutionResult(boolean hasResultSet, int updateCount, List<Map<String, Object>> rows) {
    public SqlExecutionResult {
        // Map.copyOf/Map.of reject null values, but a SQL NULL column is a routine, valid result - not an error case.
        rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
    }

    static SqlExecutionResult query(List<Map<String, Object>> rows) {
        return new SqlExecutionResult(true, -1, rows);
    }

    static SqlExecutionResult update(int updateCount) {
        return new SqlExecutionResult(false, updateCount, List.of());
    }
}
