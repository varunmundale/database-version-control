package org.example.core.differ;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;

/**
 * One column, matched by stable id, that differs between two sides. {@link Side#BOTH} only means both branches
 * describe the column differently (a rename included); whether that's a conflict is {@link SideChanges}' call.
 */
public record ColumnDiff(StableId id, ColumnModel left, ColumnModel right) implements ElementDiff<ColumnModel> {
    public ColumnDiff {
        Side.of(left, right); // validates at least one side is present
    }

    /** The name to sort and label this diff by - whichever side has the column. */
    public String columnName() {
        return left != null ? left.name() : right.name();
    }

    /** True when both sides have the column but under different names - a rename on one side, a no-op or different rename on the other. */
    public boolean isRename() {
        return left != null && right != null && !left.name().equals(right.name());
    }
}
