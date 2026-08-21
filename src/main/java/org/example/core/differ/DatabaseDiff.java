package org.example.core.differ;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Computes the diff between two branches' internal schema representations, as one {@link TableDiff} per table that
 * differs. Each table is its own independent schema object, so the diff is computed one table at a time: tables
 * are matched by name, and within a table, columns are matched by stable id - which, thanks to
 * {@link org.example.core.replayer.Replayer}'s rename-aware replay, survives a {@code RENAME COLUMN} on either
 * side. Results are ordered by table name, then by column name within each table.
 *
 * <p>An instantiable collaborator, like every other class in this codebase that does real work (see
 * {@link org.example.core.replayer.Replayer}, {@link org.example.core.replayer.SchemaOperationApplier}) - not a static
 * utility holder - even though it currently has no state or configuration of its own.
 */
public final class DatabaseDiff {

    public List<TableDiff> diff(Collection<TableModel> left, Collection<TableModel> right) {
        Map<String, TableModel> leftByName = indexBy(left, TableModel::name);
        Map<String, TableModel> rightByName = indexBy(right, TableModel::name);

        List<TableDiff> tableDiffs = new ArrayList<>();
        for (String tableName : union(leftByName.keySet(), rightByName.keySet())) {
            TableDiff tableDiff = diffTable(tableName, leftByName.get(tableName), rightByName.get(tableName));
            if (!tableDiff.isEmpty()) {
                tableDiffs.add(tableDiff);
            }
        }
        tableDiffs.sort(Comparator.comparing(TableDiff::tableName));
        return tableDiffs;
    }

    private TableDiff diffTable(String tableName, TableModel left, TableModel right) {
        return new TableDiff(tableName, left, right, diffColumns(left, right));
    }

    private List<ColumnDiff> diffColumns(TableModel left, TableModel right) {
        Collection<ColumnModel> leftColumns = left == null ? List.of() : left.columns();
        Collection<ColumnModel> rightColumns = right == null ? List.of() : right.columns();
        Map<StableId, ColumnModel> leftById = indexBy(leftColumns, ColumnModel::id);
        Map<StableId, ColumnModel> rightById = indexBy(rightColumns, ColumnModel::id);

        List<ColumnDiff> diffs = new ArrayList<>();
        for (StableId id : union(leftById.keySet(), rightById.keySet())) {
            ColumnModel leftColumn = leftById.get(id);
            ColumnModel rightColumn = rightById.get(id);
            if (leftColumn == null || rightColumn == null || leftColumn.differsFrom(rightColumn)) {
                diffs.add(new ColumnDiff(id, leftColumn, rightColumn));
            }
        }
        diffs.sort(Comparator.comparing(ColumnDiff::columnName));
        return diffs;
    }

    private static <K, V> Map<K, V> indexBy(Collection<V> items, Function<V, K> key) {
        Map<K, V> map = new LinkedHashMap<>();
        items.forEach(item -> map.put(key.apply(item), item));
        return map;
    }

    private static <K> Set<K> union(Set<K> left, Set<K> right) {
        Set<K> keys = new LinkedHashSet<>(left);
        keys.addAll(right);
        return keys;
    }
}
