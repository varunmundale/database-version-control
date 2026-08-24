package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.versioning.ChangeSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link HistoryDiff} as the tree {@code dbgit diff} prints - purely presentation, computing nothing
 * itself. A node per table, a node per differing object under it marked {@code (conflicting)} when both branches
 * changed it, and beneath that every statement, {@code >} for left or {@code <} for right.
 *
 * <pre>
 * left vs right
 * - orders
 *   |- total (conflicting)
 *     |- &gt; ALTER TABLE orders ...
 *     |- &lt; ALTER TABLE orders ...
 * - scratch (conflicting)
 *   |- &gt; ALTER TABLE scratch RENAME TO staging;
 *   |- &lt; DROP TABLE scratch;
 * </pre>
 *
 * <p>A table present on only one side prints just that statement - everything it carried went with it.
 */
public final class HistoryDiffFormatter {

    /** One line per tree node; empty when the two histories don't diverge at all. */
    public List<String> format(String left, String right, HistoryDiff diff) {
        if (diff.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(left + " vs " + right);
        diff.tables().forEach(tableDiff -> appendTable(lines, tableDiff, diff));
        return lines;
    }

    private static void appendTable(List<String> lines, TableDiff tableDiff, HistoryDiff diff) {
        lines.add("- " + tableDiff.tableName() + (diff.isConflicting(tableDiff.id()) ? " (conflicting)" : ""));
        appendStatements(lines, "  |- ", ">", diff.leftStatements(tableDiff.id()));
        appendStatements(lines, "  |- ", "<", diff.rightStatements(tableDiff.id()));
        if (tableDiff.side() != Side.BOTH) {
            return; // created or dropped - the statement above already accounts for everything it carried
        }
        for (ColumnDiff columnDiff : tableDiff.columnDiffs()) {
            appendNode(lines, columnDiff.columnName(), columnDiff.id(), diff);
        }
        for (ConstraintDiff constraintDiff : tableDiff.constraintDiffs()) {
            appendNode(lines, constraintDiff.constraintName() + " (" + kindOf(constraintDiff) + ")",
                    constraintDiff.id(), diff);
        }
        for (IndexDiff indexDiff : tableDiff.indexDiffs()) {
            appendNode(lines, indexDiff.indexName() + " (" + kindOf(indexDiff) + ")", indexDiff.id(), diff);
        }
    }

    /** One node of the tree, with every statement each side ran against that object nested underneath it. */
    private static void appendNode(List<String> lines, String label, StableId id, HistoryDiff diff) {
        lines.add("  |- " + label + (diff.isConflicting(id) ? " (conflicting)" : ""));
        appendStatements(lines, "    |- ", ">", diff.leftStatements(id));
        appendStatements(lines, "    |- ", "<", diff.rightStatements(id));
    }

    private static void appendStatements(List<String> lines, String prefix, String marker, List<ChangeSet> statements) {
        statements.forEach(changeset -> lines.add(prefix + marker + " " + changeset.ddl()));
    }

    private static String kindOf(ConstraintDiff constraintDiff) {
        return constraintDiff.left() != null ? constraintDiff.left().definition() : constraintDiff.right().definition();
    }

    private static String kindOf(IndexDiff indexDiff) {
        return indexDiff.left() != null ? indexDiff.left().definition() : indexDiff.right().definition();
    }
}
