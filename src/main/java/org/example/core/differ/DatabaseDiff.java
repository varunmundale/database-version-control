package org.example.core.differ;

import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes the diff between two branches' internal schema representations, as one {@link TableDiff} per table that
 * differs. Each table is its own independent schema object, so the diff is computed one table at a time: this class
 * pairs tables up by name and leaves {@link TableDiff#between} to work out what changed inside each pairing.
 * Results are ordered by table name.
 *
 * <p>An instantiable collaborator, like every other class in this codebase that does real work (see
 * {@link org.example.core.replayer.Replayer}, {@link org.example.core.replayer.SchemaOperationApplier}) - not a static
 * utility holder - even though it currently has no state or configuration of its own.
 */
public final class DatabaseDiff {

    public List<TableDiff> diff(Collection<TableModel> left, Collection<TableModel> right) {
        Map<String, TableModel> leftByName = tablesByName(left);
        Map<String, TableModel> rightByName = tablesByName(right);

        Set<String> tableNames = new LinkedHashSet<>(leftByName.keySet());
        tableNames.addAll(rightByName.keySet());

        List<TableDiff> tableDiffs = new ArrayList<>();
        for (String tableName : tableNames) {
            TableDiff tableDiff = TableDiff.between(tableName, leftByName.get(tableName), rightByName.get(tableName));
            if (!tableDiff.isEmpty()) {
                tableDiffs.add(tableDiff);
            }
        }
        tableDiffs.sort(Comparator.comparing(TableDiff::tableName));
        return tableDiffs;
    }

    private static Map<String, TableModel> tablesByName(Collection<TableModel> tables) {
        Map<String, TableModel> byName = new LinkedHashMap<>();
        tables.forEach(table -> byName.put(table.name(), table));
        return byName;
    }
}
