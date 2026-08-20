package org.example.diff;

import org.example.model.schema.ColumnModel;
import org.example.model.schema.StableId;
import org.example.model.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares two branches' internal schema representations. Each table is its own independent schema object, so the
 * diff is computed one table at a time: tables are matched by name (a table rename is not tracked - it shows up
 * as one table disappearing and a different one appearing, not as a rename), and within a table, columns are
 * matched by stable id, which - thanks to {@link org.example.replay.SchemaReplayer}'s rename-aware replay -
 * survives a {@code RENAME COLUMN} on either side. Output is ordered by table name, then by column name within
 * each table.
 */
public final class SchemaDiff {
    public enum Side { LEFT, RIGHT, CONFLICT }

    /** One difference between the two sides, anchored to the stable id of the table or column it concerns. */
    public record Entry(StableId id, String description, Side side) {
    }

    public static List<Entry> diff(Collection<TableModel> left, Collection<TableModel> right) {
        Map<String, TableModel> leftByName = byName(left);
        Map<String, TableModel> rightByName = byName(right);
        Set<String> tableNames = new TreeSet<>();
        tableNames.addAll(leftByName.keySet());
        tableNames.addAll(rightByName.keySet());

        List<Entry> entries = new ArrayList<>();
        for (String tableName : tableNames) {
            entries.addAll(diffTable(tableName, leftByName.get(tableName), rightByName.get(tableName)));
        }
        return entries;
    }

    /** Diffs one table, present on one or both sides, in isolation from every other table. */
    private static List<Entry> diffTable(String tableName, TableModel left, TableModel right) {
        List<Entry> entries = new ArrayList<>();
        if (left != null && right == null) {
            entries.add(new Entry(left.id(), "table " + tableName, Side.LEFT));
        } else if (left == null && right != null) {
            entries.add(new Entry(right.id(), "table " + tableName, Side.RIGHT));
        }
        entries.addAll(diffColumns(left, right));
        return entries;
    }

    /**
     * Columns are matched by stable id, which can legitimately carry a different name on each side (one branch
     * renamed it, the other didn't, or renamed it differently); that mismatch is itself a conflict, distinct from
     * - but reported alongside - a plain signature difference. Results come back ordered by column name.
     */
    private static List<Entry> diffColumns(TableModel left, TableModel right) {
        Map<StableId, ColumnRef> leftColumns = columnsById(left);
        Map<StableId, ColumnRef> rightColumns = columnsById(right);
        List<NamedEntry> drafts = new ArrayList<>();

        for (Map.Entry<StableId, ColumnRef> entry : leftColumns.entrySet()) {
            ColumnRef leftColumn = entry.getValue();
            ColumnRef rightColumn = rightColumns.get(entry.getKey());
            if (rightColumn == null) {
                drafts.add(named(leftColumn.columnName(), entry.getKey(), "column " + leftColumn.label(), Side.LEFT));
            } else if (!leftColumn.columnName().equals(rightColumn.columnName())) {
                drafts.add(named(leftColumn.columnName(), entry.getKey(), "column " + leftColumn.label() + " renamed to '"
                        + rightColumn.columnName() + "' on the other side (left: " + leftColumn.signature()
                        + ", right: " + rightColumn.signature() + ")", Side.CONFLICT));
            } else if (!leftColumn.signature().equals(rightColumn.signature())) {
                drafts.add(named(leftColumn.columnName(), entry.getKey(), "column " + leftColumn.label()
                        + " (left: " + leftColumn.signature() + ", right: " + rightColumn.signature() + ")", Side.CONFLICT));
            }
        }
        for (Map.Entry<StableId, ColumnRef> entry : rightColumns.entrySet()) {
            if (!leftColumns.containsKey(entry.getKey())) {
                drafts.add(named(entry.getValue().columnName(), entry.getKey(), "column " + entry.getValue().label(), Side.RIGHT));
            }
        }

        return drafts.stream().sorted(Comparator.comparing(NamedEntry::columnName)).map(NamedEntry::entry).toList();
    }

    private static NamedEntry named(String columnName, StableId id, String description, Side side) {
        return new NamedEntry(columnName, new Entry(id, description, side));
    }

    private static Map<String, TableModel> byName(Collection<TableModel> tables) {
        Map<String, TableModel> byName = new LinkedHashMap<>();
        for (TableModel table : tables) {
            byName.put(table.name(), table);
        }
        return byName;
    }

    /** @param table {@code null} if the table does not exist on this side */
    private static Map<StableId, ColumnRef> columnsById(TableModel table) {
        Map<StableId, ColumnRef> columns = new LinkedHashMap<>();
        if (table == null) {
            return columns;
        }
        for (ColumnModel column : table.columns()) {
            columns.put(column.id(), new ColumnRef(table.name(), column.name(), signature(column)));
        }
        return columns;
    }

    private static String signature(ColumnModel column) {
        return column.nativeType() + (column.nullable() ? "" : " NOT NULL")
                + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue());
    }

    private record ColumnRef(String tableName, String columnName, String signature) {
        String label() {
            return tableName + "." + columnName;
        }
    }

    private record NamedEntry(String columnName, Entry entry) {
    }

    private SchemaDiff() {
    }
}
