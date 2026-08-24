package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Diffs two branches' schemas, as one {@link TableDiff} per table that differs. Tables are paired by stable id, not
 * name, so a rename on either side reads as one table whose name differs rather than a drop plus an add; each
 * pairing's contents are worked out by {@link TableDiff#between}. Results are ordered by table name, then id.
 */
public final class DatabaseDiff {

    public List<TableDiff> diff(Collection<TableModel> left, Collection<TableModel> right) {
        Map<StableId, TableModel> leftById = TableDiff.byId(left);
        Map<StableId, TableModel> rightById = TableDiff.byId(right);

        Set<StableId> tableIds = new LinkedHashSet<>(leftById.keySet());
        tableIds.addAll(rightById.keySet());

        List<TableDiff> tableDiffs = new ArrayList<>();
        for (StableId id : tableIds) {
            TableDiff tableDiff = TableDiff.between(leftById.get(id), rightById.get(id));
            if (!tableDiff.isEmpty()) {
                tableDiffs.add(tableDiff);
            }
        }
        tableDiffs.sort(Comparator.comparing(TableDiff::tableName).thenComparing(diff -> diff.id().value()));
        return tableDiffs;
    }
}
