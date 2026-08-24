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
 * Computes the diff between two branches' internal schema representations, as one {@link TableDiff} per table that
 * differs. Each table is its own independent schema object, so the diff is computed one table at a time: this class
 * pairs tables up by stable id - not by name, which is what lets a rename on either side read as one table whose
 * name differs rather than one table dropped and a different one added - and leaves {@link TableDiff#between} to
 * work out what changed inside each pairing. Results are ordered by table name, then by id to keep the order
 * deterministic on the rare occasion a rename leaves two different tables sharing a display name.
 *
 * <p>An instantiable collaborator, like every other class in this codebase that does real work (see
 * {@link org.example.core.replayer.Replayer}, {@link org.example.core.replayer.SchemaOperationApplier}) - not a static
 * utility holder - even though it currently has no state or configuration of its own.
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
