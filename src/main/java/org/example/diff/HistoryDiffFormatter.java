package org.example.diff;

import org.example.model.schema.ColumnModel;
import org.example.model.schema.StableId;
import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.replay.SchemaReplayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Renders the divergence between two branches' commit histories as a tree: finds the lowest common ancestor
 * commit (the longest shared prefix of both histories), then - for every table touched beyond it - a node per
 * column that actually differs, each listing every statement run against it on that side, marked {@code >} for
 * the left branch or {@code <} for the right. A column {@link DatabaseDiff} finds genuinely conflicting (matched
 * by stable id, so a rename on one side racing a modification on the other still counts) is labeled as such, with
 * both sides' statements nested underneath it - "bringing them together" without needing a separate rendering
 * for conflicting vs. non-conflicting columns.
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
    private static final String SCHEMA = "public";

    private final SchemaReplayer schemaReplayer;
    private final DatabaseDiff databaseDiff;

    public HistoryDiffFormatter() {
        this(new SchemaReplayer(), new DatabaseDiff());
    }

    public HistoryDiffFormatter(SchemaReplayer schemaReplayer, DatabaseDiff databaseDiff) {
        this.schemaReplayer = Objects.requireNonNull(schemaReplayer, "schemaReplayer must not be null");
        this.databaseDiff = Objects.requireNonNull(databaseDiff, "databaseDiff must not be null");
    }

    /** One line per tree node; empty when the two histories don't diverge. */
    public List<String> format(String left, String right, List<ChangeSet> leftHistory, List<ChangeSet> rightHistory) {
        int commonAncestorLength = commonPrefixLength(leftHistory, rightHistory);
        List<ChangeSet> leftOnly = leftHistory.subList(commonAncestorLength, leftHistory.size());
        List<ChangeSet> rightOnly = rightHistory.subList(commonAncestorLength, rightHistory.size());
        if (leftOnly.isEmpty() && rightOnly.isEmpty()) {
            return List.of();
        }

        Map<StableId, List<ChangeSet>> leftByColumn = changesetsByColumn(leftHistory, commonAncestorLength);
        Map<StableId, List<ChangeSet>> rightByColumn = changesetsByColumn(rightHistory, commonAncestorLength);
        Map<String, TableDiff> tableDiffsByName = tableDiffsByName(leftHistory, rightHistory);

        TreeSet<String> touchedTables = new TreeSet<>();
        leftOnly.forEach(changeset -> touchedTables.add(tableName(changeset)));
        rightOnly.forEach(changeset -> touchedTables.add(tableName(changeset)));

        List<String> lines = new ArrayList<>();
        lines.add(left + " vs " + right);
        for (String table : touchedTables) {
            appendTable(lines, table, tableDiffsByName.get(table), leftByColumn, rightByColumn);
        }
        return lines;
    }

    private Map<String, TableDiff> tableDiffsByName(List<ChangeSet> leftHistory, List<ChangeSet> rightHistory) {
        Map<String, TableModel> leftSchema = schemaReplayer.replay(leftHistory);
        Map<String, TableModel> rightSchema = schemaReplayer.replay(rightHistory);
        Map<String, TableDiff> byName = new LinkedHashMap<>();
        databaseDiff.diff(leftSchema.values(), rightSchema.values()).forEach(tableDiff -> byName.put(tableDiff.tableName(), tableDiff));
        return byName;
    }

    private void appendTable(List<String> lines, String table, TableDiff tableDiff,
                              Map<StableId, List<ChangeSet>> leftByColumn, Map<StableId, List<ChangeSet>> rightByColumn) {
        lines.add("- " + table);
        if (tableDiff == null) {
            return;
        }
        for (ColumnDiff columnDiff : tableDiff.columnDiffs()) {
            String label = columnDiff.columnName() + (columnDiff.side() == Side.CONFLICT ? " (conflicting)" : "");
            lines.add("  |- " + label);
            statementsFor(columnDiff.left(), leftByColumn).forEach(changeset -> lines.add("    |- > " + changeset.ddl()));
            statementsFor(columnDiff.right(), rightByColumn).forEach(changeset -> lines.add("    |- < " + changeset.ddl()));
        }
    }

    private static List<ChangeSet> statementsFor(ColumnModel column, Map<StableId, List<ChangeSet>> byColumn) {
        return column == null ? List.of() : byColumn.getOrDefault(column.id(), List.of());
    }

    /** Every changeset from {@code divergeFrom} onward, keyed by the column id it created or changed. */
    private Map<StableId, List<ChangeSet>> changesetsByColumn(List<ChangeSet> history, int divergeFrom) {
        Map<String, TableModel> tables = new LinkedHashMap<>();
        Map<StableId, List<ChangeSet>> byColumn = new LinkedHashMap<>();
        for (int i = 0; i < history.size(); i++) {
            ChangeSet changeset = history.get(i);
            String table = tableName(changeset);
            TableModel before = tables.get(table);
            TableModel after = schemaReplayer.apply(SCHEMA, changeset.ddl(), before);
            tables.put(table, after);
            if (i >= divergeFrom) {
                recordTouchedColumns(before, after, changeset, byColumn);
            }
        }
        return byColumn;
    }

    private static void recordTouchedColumns(TableModel before, TableModel after, ChangeSet changeset,
                                              Map<StableId, List<ChangeSet>> byColumn) {
        Map<StableId, ColumnModel> beforeColumns = new LinkedHashMap<>();
        if (before != null) {
            before.columns().forEach(column -> beforeColumns.put(column.id(), column));
        }
        for (ColumnModel column : after.columns()) {
            ColumnModel previous = beforeColumns.get(column.id());
            if (previous == null || previous.differsFrom(column)) {
                byColumn.computeIfAbsent(column.id(), unused -> new ArrayList<>()).add(changeset);
            }
        }
    }

    private String tableName(ChangeSet changeset) {
        return schemaReplayer.tableName(changeset.ddl());
    }

    /** How far the two histories agree before diverging - i.e. the lowest common ancestor commit's position. */
    private static int commonPrefixLength(List<ChangeSet> left, List<ChangeSet> right) {
        int length = Math.min(left.size(), right.size());
        int common = 0;
        while (common < length && left.get(common).id() == right.get(common).id()) {
            common++;
        }
        return common;
    }
}
