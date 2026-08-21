package org.example.core.differ;

import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;

/**
 * One index, matched by stable id, that differs between two sides - see {@link #side()}. A {@link Side#CONFLICT}
 * means both branches have an index of this name but defined differently, e.g. unique on one side only.
 */
public record IndexDiff(StableId id, IndexModel left, IndexModel right) {
    public IndexDiff {
        Side.of(left, right); // validates at least one side is present
    }

    public Side side() {
        return Side.of(left, right);
    }

    /** The name to sort and label this diff by - whichever side has the index. */
    public String indexName() {
        return left != null ? left.name() : right.name();
    }
}
