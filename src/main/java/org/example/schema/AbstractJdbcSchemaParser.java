package org.example.schema;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Shared JDBC metadata conversion, with small vendor adapters selecting their dialect and default schema. */
public abstract class AbstractJdbcSchemaParser implements SchemaParser {
    protected abstract String dialect();

    @Override
    public DatabaseSchema parse(Connection connection) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        return parse(connection, defaultSchema(connection));
    }

    @Override
    public DatabaseSchema parse(Connection connection, String schema) throws SQLException {
        Objects.requireNonNull(connection, "connection must not be null");
        Objects.requireNonNull(schema, "schema must not be null");
        DatabaseMetaData metadata = connection.getMetaData();
        List<TableModel> tables = new ArrayList<>();
        try (ResultSet resultSet = metadata.getTables(connection.getCatalog(), schema, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                tables.add(readTable(metadata, resultSet.getString("TABLE_SCHEM"), resultSet.getString("TABLE_NAME")));
            }
        }
        tables.sort(Comparator.comparing(TableModel::name));
        return new DatabaseSchema(dialect(), schema, tables);
    }

    protected String defaultSchema(Connection connection) throws SQLException {
        return connection.getSchema();
    }

    private TableModel readTable(DatabaseMetaData metadata, String schema, String tableName) throws SQLException {
        StableId tableId = tableId(schema, tableName);
        Map<String, ColumnModel> columnsByName = new LinkedHashMap<>();
        try (ResultSet columns = metadata.getColumns(null, schema, tableName, "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME");
                columnsByName.put(name, new ColumnModel(
                        columnId(tableId, name), name, columns.getString("TYPE_NAME"),
                        columns.getInt("ORDINAL_POSITION"),
                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        columns.getString("COLUMN_DEF")
                ));
            }
        }
        List<IndexModel> indexes = readIndexes(metadata, schema, tableName, tableId, columnsByName);
        List<ConstraintModel> constraints = readConstraints(metadata, schema, tableName, tableId, columnsByName, indexes);
        return new TableModel(tableId, schema, tableName, columnsByName.values().stream()
                .sorted(Comparator.comparingInt(ColumnModel::ordinal)).toList(), indexes, constraints);
    }

    private List<IndexModel> readIndexes(DatabaseMetaData metadata, String schema, String tableName, StableId tableId,
                                         Map<String, ColumnModel> columns) throws SQLException {
        Map<String, IndexAccumulator> indexes = new TreeMap<>();
        try (ResultSet resultSet = metadata.getIndexInfo(null, schema, tableName, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name != null && column != null) {
                    boolean unique = !resultSet.getBoolean("NON_UNIQUE");
                    indexes.computeIfAbsent(name, ignored -> new IndexAccumulator(unique))
                            .add(resultSet.getShort("ORDINAL_POSITION"), columns.get(column).id());
                }
            }
        }
        return indexes.entrySet().stream().map(entry -> new IndexModel(
                StableId.of("index", tableId.value() + "." + entry.getKey()), entry.getKey(), entry.getValue().unique,
                entry.getValue().columns.values().stream().toList())).toList();
    }

    private List<ConstraintModel> readConstraints(DatabaseMetaData metadata, String schema, String tableName,
                                                   StableId tableId, Map<String, ColumnModel> columns,
                                                   List<IndexModel> indexes) throws SQLException {
        List<ConstraintModel> constraints = new ArrayList<>();
        Map<String, KeyAccumulator> primaryKeys = new TreeMap<>();
        try (ResultSet resultSet = metadata.getPrimaryKeys(null, schema, tableName)) {
            while (resultSet.next()) {
                String name = Objects.toString(resultSet.getString("PK_NAME"), "PRIMARY_KEY");
                primaryKeys.computeIfAbsent(name, ignored -> new KeyAccumulator()).add(resultSet.getShort("KEY_SEQ"),
                        columns.get(resultSet.getString("COLUMN_NAME")).id());
            }
        }
        primaryKeys.forEach((name, key) -> constraints.add(constraint(tableId, name, ConstraintType.PRIMARY_KEY,
                key.columns.values().stream().toList(), null, List.of())));
        for (IndexModel index : indexes) {
            if (index.unique() && primaryKeys.values().stream().noneMatch(key -> key.columns.values().stream().toList().equals(index.columnIds()))) {
                constraints.add(constraint(tableId, index.name(), ConstraintType.UNIQUE, index.columnIds(), null, List.of()));
            }
        }
        Map<String, ForeignKeyAccumulator> foreignKeys = new TreeMap<>();
        try (ResultSet resultSet = metadata.getImportedKeys(null, schema, tableName)) {
            while (resultSet.next()) {
                String name = Objects.toString(resultSet.getString("FK_NAME"), "FOREIGN_KEY_" + foreignKeys.size());
                StableId referencedTable = tableId(resultSet.getString("PKTABLE_SCHEM"), resultSet.getString("PKTABLE_NAME"));
                foreignKeys.computeIfAbsent(name, ignored -> new ForeignKeyAccumulator(referencedTable))
                        .add(resultSet.getShort("KEY_SEQ"), columns.get(resultSet.getString("FKCOLUMN_NAME")).id(),
                                columnId(referencedTable, resultSet.getString("PKCOLUMN_NAME")));
            }
        }
        foreignKeys.forEach((name, key) -> constraints.add(constraint(tableId, name, ConstraintType.FOREIGN_KEY,
                key.columns.values().stream().toList(), key.referencedTable, key.referencedColumns.values().stream().toList())));
        return List.copyOf(constraints);
    }

    private static ConstraintModel constraint(StableId tableId, String name, ConstraintType type, List<StableId> columns,
                                               StableId referencedTable, List<StableId> referencedColumns) {
        return new ConstraintModel(StableId.of("constraint", tableId.value() + "." + name), name, type, columns,
                referencedTable, referencedColumns);
    }

    private static StableId tableId(String schema, String table) {
        return StableId.of("table", schema + "." + table);
    }

    private static StableId columnId(StableId tableId, String column) {
        return StableId.of("column", tableId.value() + "." + column);
    }

    private static final class IndexAccumulator {
        private final boolean unique;
        private final Map<Short, StableId> columns = new TreeMap<>();
        private IndexAccumulator(boolean unique) { this.unique = unique; }
        private void add(short position, StableId column) { columns.put(position, column); }
    }

    private static final class KeyAccumulator {
        private final Map<Short, StableId> columns = new TreeMap<>();
        private void add(short position, StableId column) { columns.put(position, column); }
    }

    private static final class ForeignKeyAccumulator {
        private final StableId referencedTable;
        private final Map<Short, StableId> columns = new TreeMap<>();
        private final Map<Short, StableId> referencedColumns = new TreeMap<>();
        private ForeignKeyAccumulator(StableId referencedTable) { this.referencedTable = referencedTable; }
        private void add(short position, StableId column, StableId referencedColumn) {
            columns.put(position, column);
            referencedColumns.put(position, referencedColumn);
        }
    }
}
