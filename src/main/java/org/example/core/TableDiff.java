package org.example.core;

import org.example.models.schema.TableModel;

import java.util.List;

/**
 * One table, matched by name (a table rename is not tracked, so a renamed table shows up as one disappearing and a
 * different one appearing, not as a rename), and everything that differs about it between two sides. Exactly one
 * of {@code left}/{@code right} is {@code null} when the table exists on only one side - see {@link #side()}. Note
 * that {@link Side#CONFLICT} here just means "present on both sides"; unlike a {@link ColumnDiff}, that is not
 * itself noteworthy - {@code columnDiffs} is what says whether the table actually changed.
 */
public record TableDiff(String tableName, TableModel left, TableModel right, List<ColumnDiff> columnDiffs) {
    public TableDiff {
        Side.of(left, right); // validates at least one side is present
        columnDiffs = List.copyOf(columnDiffs);
    }

    /** LEFT/RIGHT when the table exists on only one side; CONFLICT when it exists on both, regardless of whether anything inside it differs. */
    public Side side() {
        return Side.of(left, right);
    }

    public boolean onlyOnLeft() {
        return side() == Side.LEFT;
    }

    public boolean onlyOnRight() {
        return side() == Side.RIGHT;
    }

    /** True when the table exists on both sides and none of its columns differ - i.e. there is nothing to report. */
    public boolean isEmpty() {
        return side() == Side.CONFLICT && columnDiffs.isEmpty();
    }
}
