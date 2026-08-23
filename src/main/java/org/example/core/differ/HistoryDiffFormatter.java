package org.example.core.differ;

import org.example.models.schema.StableId;
import org.example.models.versioning.ChangeSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link HistoryDiff} as the tree {@code dbgit diff} prints. Purely presentation: it computes nothing
 * and reads no history - {@link Differ} has already worked out which tables differ, which columns, constraints and
 * indexes within them, and which statements each side ran against each of those. This class only decides what that
 * looks like on a terminal: a node per table, a node per object under it marked {@code (conflicting)} when the
 * diff says both branches changed it since they diverged, and beneath it every statement, marked {@code >} for the
 * left branch or {@code <} for the right - which is what brings both sides of a conflict together without needing
 * a second rendering for it.
 *
 * <pre>
 * left vs right
 * - orders
 *   |- total (conflicting)
 *     |- &gt; ALTER TABLE orders ...
 *     |- &lt; ALTER TABLE orders ...
 * </pre>
 */
public final class HistoryDiffFormatter {

    /**
     * One line per tree node; empty when the two histories don't diverge at all. A diff that diverged but nets out
     * to the same schema - a column added and dropped again on one side - is the header alone, since no table,
     * column, constraint or index has anything to report.
     */
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
        lines.add("- " + tableDiff.tableName());
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

    /**
     * One node of the tree, with every statement each side ran against that object nested underneath it. Columns,
     * constraints and indexes all key off a stable id, so one lookup serves all three.
     */
    private static void appendNode(List<String> lines, String label, StableId id, HistoryDiff diff) {
        lines.add("  |- " + label + (diff.isConflicting(id) ? " (conflicting)" : ""));
        appendStatements(lines, "> ", diff.leftStatements(id));
        appendStatements(lines, "< ", diff.rightStatements(id));
    }

    private static void appendStatements(List<String> lines, String marker, List<ChangeSet> statements) {
        statements.forEach(changeset -> lines.add("    |- " + marker + changeset.ddl()));
    }

    private static String kindOf(ConstraintDiff constraintDiff) {
        return constraintDiff.left() != null ? constraintDiff.left().definition() : constraintDiff.right().definition();
    }

    private static String kindOf(IndexDiff indexDiff) {
        return indexDiff.left() != null ? indexDiff.left().definition() : indexDiff.right().definition();
    }
}
