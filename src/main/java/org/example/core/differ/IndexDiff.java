package org.example.core.differ;

import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;

/**
 * One index, matched by stable id, that differs between two sides. {@link Side#BOTH} means both branches defined
 * an index of this name differently - a conflict only if both actually changed it ({@link SideChanges}).
 */
public record IndexDiff(StableId id, IndexModel left, IndexModel right) implements ElementDiff<IndexModel> {
    public IndexDiff {
        Side.of(left, right); // validates at least one side is present
    }

    /** The name to sort and label this diff by - whichever side has the index. */
    public String indexName() {
        return left != null ? left.name() : right.name();
    }
}
