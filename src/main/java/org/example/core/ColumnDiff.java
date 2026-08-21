package org.example.core;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.StableId;

/**
 * One column, matched by stable id, that differs between two sides - see {@link #side()}. A {@link Side#CONFLICT}
 * includes a rename on one side racing a plain modification on the other, since a stable id can legitimately carry
 * a different name on each side. Carries the actual {@link ColumnModel}s rather than a pre-rendered message, so
 * callers decide how (or whether) to display a conflict.
 */
public record ColumnDiff(StableId id, ColumnModel left, ColumnModel right) {
    public ColumnDiff {
        Side.of(left, right); // validates at least one side is present
    }

    public Side side() {
        return Side.of(left, right);
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
