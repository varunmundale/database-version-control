package org.example.core.differ;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;

/**
 * One column, matched by stable id, that differs between two sides - see {@link #side()}. {@link Side#BOTH} says
 * only that both branches have the column and describe it differently, which includes a rename on one side (a
 * stable id can legitimately carry a different name on each side); whether that is a conflict depends on what the
 * column looked like before the two branches diverged, and is {@link SideChanges}' call. Carries the actual
 * {@link ColumnModel}s rather than a pre-rendered message, so callers decide how (or whether) to display it.
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
