package org.example.core.differ;

import org.example.core.replayer.Replayer;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Renders the divergence between two branches' commit histories as a tree: finds the lowest common ancestor
 * commit (the longest shared prefix of both histories), then - for every table touched beyond it - a node per
 * column, constraint and index that actually differs, each listing every statement run against it on that side,
 * marked {@code >} for the left branch or {@code <} for the right. A column {@link DatabaseDiff} finds genuinely conflicting (matched
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

    private final Replayer replayer;
    private final DatabaseDiff databaseDiff;

    public HistoryDiffFormatter() {
        this(new Replayer(), new DatabaseDiff());
    }

    public HistoryDiffFormatter(Replayer replayer, DatabaseDiff databaseDiff) {
        this.replayer = Objects.requireNonNull(replayer, "replayer must not be null");
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

        Map<StableId, List<ChangeSet>> leftByObject = changesetsByObject(leftHistory, commonAncestorLength);
        Map<StableId, List<ChangeSet>> rightByObject = changesetsByObject(rightHistory, commonAncestorLength);
        Map<String, TableDiff> tableDiffsByName = tableDiffsByName(leftHistory, rightHistory);

        TreeSet<String> touchedTables = new TreeSet<>();
        leftOnly.forEach(changeset -> touchedTables.add(tableName(changeset)));
        rightOnly.forEach(changeset -> touchedTables.add(tableName(changeset)));

        List<String> lines = new ArrayList<>();
        lines.add(left + " vs " + right);
        for (String table : touchedTables) {
            appendTable(lines, table, tableDiffsByName.get(table), leftByObject, rightByObject);
        }
        return lines;
    }

    private Map<String, TableDiff> tableDiffsByName(List<ChangeSet> leftHistory, List<ChangeSet> rightHistory) {
        Map<String, TableModel> leftSchema = replayer.replay(leftHistory);
        Map<String, TableModel> rightSchema = replayer.replay(rightHistory);
        Map<String, TableDiff> byName = new LinkedHashMap<>();
        databaseDiff.diff(leftSchema.values(), rightSchema.values()).forEach(tableDiff -> byName.put(tableDiff.tableName(), tableDiff));
        return byName;
    }

    private void appendTable(List<String> lines, String table, TableDiff tableDiff,
                              Map<StableId, List<ChangeSet>> leftByObject, Map<StableId, List<ChangeSet>> rightByObject) {
        lines.add("- " + table);
        if (tableDiff == null) {
            return;
        }
        for (ColumnDiff columnDiff : tableDiff.columnDiffs()) {
            appendNode(lines, columnDiff.columnName(), columnDiff.side(), columnDiff.id(), leftByObject, rightByObject);
        }
        for (ConstraintDiff constraintDiff : tableDiff.constraintDiffs()) {
            appendNode(lines, constraintDiff.constraintName() + " (" + kindOf(constraintDiff) + ")",
                    constraintDiff.side(), constraintDiff.id(), leftByObject, rightByObject);
        }
        for (IndexDiff indexDiff : tableDiff.indexDiffs()) {
            appendNode(lines, indexDiff.indexName() + " (" + kindOf(indexDiff) + ")",
                    indexDiff.side(), indexDiff.id(), leftByObject, rightByObject);
        }
    }

    /**
     * One node of the tree, with every statement each side ran against that object nested underneath it. Columns,
     * constraints and indexes all key off a stable id, so one lookup serves all three.
     */
    private static void appendNode(List<String> lines, String label, Side side, StableId id,
                                    Map<StableId, List<ChangeSet>> leftByObject, Map<StableId, List<ChangeSet>> rightByObject) {
        lines.add("  |- " + label + (side == Side.CONFLICT ? " (conflicting)" : ""));
        leftByObject.getOrDefault(id, List.of()).forEach(changeset -> lines.add("    |- > " + changeset.ddl()));
        rightByObject.getOrDefault(id, List.of()).forEach(changeset -> lines.add("    |- < " + changeset.ddl()));
    }

    private static String kindOf(ConstraintDiff constraintDiff) {
        return constraintDiff.left() != null ? constraintDiff.left().definition() : constraintDiff.right().definition();
    }

    private static String kindOf(IndexDiff indexDiff) {
        return indexDiff.left() != null ? indexDiff.left().definition() : indexDiff.right().definition();
    }

    /** Every changeset from {@code divergeFrom} onward, keyed by the id of each object it created or changed. */
    private Map<StableId, List<ChangeSet>> changesetsByObject(List<ChangeSet> history, int divergeFrom) {
        Map<String, TableModel> tables = new LinkedHashMap<>();
        Map<StableId, List<ChangeSet>> byObject = new LinkedHashMap<>();
        for (int i = 0; i < history.size(); i++) {
            ChangeSet changeset = history.get(i);
            String table = tableName(changeset);
            TableModel before = tables.get(table);
            TableModel after = replayer.apply(SCHEMA, changeset.ddl(), before);
            tables.put(table, after);
            if (i >= divergeFrom) {
                recordTouched(before, after, changeset, byObject);
            }
        }
        return byObject;
    }

    /**
     * What one statement touched is the same question {@link TableDiff#between} already answers - so ask it, with
     * the table as it stood before the statement on one side and after it on the other.
     */
    private static void recordTouched(TableModel before, TableModel after, ChangeSet changeset,
                                       Map<StableId, List<ChangeSet>> byObject) {
        TableDiff delta = TableDiff.between(after.name(), before, after);
        delta.columnDiffs().forEach(columnDiff -> record(byObject, columnDiff.id(), changeset));
        delta.constraintDiffs().forEach(constraintDiff -> record(byObject, constraintDiff.id(), changeset));
        delta.indexDiffs().forEach(indexDiff -> record(byObject, indexDiff.id(), changeset));
    }

    private static void record(Map<StableId, List<ChangeSet>> byObject, StableId id, ChangeSet changeset) {
        byObject.computeIfAbsent(id, unused -> new ArrayList<>()).add(changeset);
    }

    private String tableName(ChangeSet changeset) {
        return replayer.tableName(changeset.ddl());
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
