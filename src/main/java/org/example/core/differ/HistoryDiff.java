package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.versioning.ChangeSet;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What {@link Differ} found between two branches' commit histories: the changesets each side has and the other
 * doesn't, every table whose fully replayed schema differs, and - by {@link StableId} - which of those exclusive
 * statements touched each column, constraint or index. Everything a consumer of a diff needs and nothing about how
 * it is displayed: {@link HistoryDiffFormatter} renders this for {@code dbgit diff}, while
 * {@link org.example.core.merger.Merger} reads the same value for conflicts and for the changesets a merge has to
 * replay.
 *
 * <p>{@link #isEmpty()} is about divergence, not about tables: two histories can diverge and still net out to the
 * same schema (a column added and dropped again on one side), which is a diff with no tables in it rather than no
 * diff at all.
 */
public record HistoryDiff(List<ChangeSet> leftOnly, List<ChangeSet> rightOnly, List<TableDiff> tables,
                          Map<StableId, List<ChangeSet>> leftStatements,
                          Map<StableId, List<ChangeSet>> rightStatements) {
    public HistoryDiff {
        Objects.requireNonNull(leftOnly, "leftOnly must not be null");
        Objects.requireNonNull(rightOnly, "rightOnly must not be null");
        Objects.requireNonNull(tables, "tables must not be null");
        Objects.requireNonNull(leftStatements, "leftStatements must not be null");
        Objects.requireNonNull(rightStatements, "rightStatements must not be null");
    }

    /** Two histories that carry exactly the same commits - nothing to report on either side. */
    static HistoryDiff identical() {
        return new HistoryDiff(List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    /** True when neither branch has a commit the other lacks. */
    public boolean isEmpty() {
        return leftOnly.isEmpty() && rightOnly.isEmpty();
    }

    /** The left branch's own statements against the object with this id, oldest first. */
    public List<ChangeSet> leftStatements(StableId id) {
        return leftStatements.getOrDefault(id, List.of());
    }

    /** The right branch's own statements against the object with this id, oldest first. */
    public List<ChangeSet> rightStatements(StableId id) {
        return rightStatements.getOrDefault(id, List.of());
    }
}
