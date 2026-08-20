package org.example.diff;

import org.example.model.schema.ColumnModel;
import org.example.model.schema.StableId;
import org.example.model.schema.TableModel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Compares two branches' internal schema representations, matching tables and columns by stable id. */
public final class SchemaDiff {
    public enum Side { LEFT, RIGHT, CONFLICT }

    /** One difference between the two sides, anchored to the stable id of the table or column it concerns. */
    public record Entry(StableId id, String description, Side side) {
    }

    public static List<Entry> diff(Collection<TableModel> left, Collection<TableModel> right) {
        List<Entry> entries = new ArrayList<>();
        diffTables(left, right, entries);
        diffColumns(left, right, entries);
        entries.sort(Comparator.comparing(entry -> entry.id().value()));
        return entries;
    }

    /** Tables are diffed by presence only - membership changes are already fully captured by their columns. */
    private static void diffTables(Collection<TableModel> left, Collection<TableModel> right, List<Entry> entries) {
        Map<StableId, String> leftTables = tableNamesById(left);
        Map<StableId, String> rightTables = tableNamesById(right);
        for (Map.Entry<StableId, String> entry : leftTables.entrySet()) {
            if (!rightTables.containsKey(entry.getKey())) {
                entries.add(new Entry(entry.getKey(), "table " + entry.getValue(), Side.LEFT));
            }
        }
        for (Map.Entry<StableId, String> entry : rightTables.entrySet()) {
            if (!leftTables.containsKey(entry.getKey())) {
                entries.add(new Entry(entry.getKey(), "table " + entry.getValue(), Side.RIGHT));
            }
        }
    }

    private static void diffColumns(Collection<TableModel> left, Collection<TableModel> right, List<Entry> entries) {
        Map<StableId, ColumnRef> leftColumns = columnsById(left);
        Map<StableId, ColumnRef> rightColumns = columnsById(right);
        for (Map.Entry<StableId, ColumnRef> entry : leftColumns.entrySet()) {
            ColumnRef leftColumn = entry.getValue();
            ColumnRef rightColumn = rightColumns.get(entry.getKey());
            if (rightColumn == null) {
                entries.add(new Entry(entry.getKey(), "column " + leftColumn.label(), Side.LEFT));
            } else if (!leftColumn.signature().equals(rightColumn.signature())) {
                entries.add(new Entry(entry.getKey(), "column " + leftColumn.label()
                        + " (left: " + leftColumn.signature() + ", right: " + rightColumn.signature() + ")", Side.CONFLICT));
            }
        }
        for (Map.Entry<StableId, ColumnRef> entry : rightColumns.entrySet()) {
            if (!leftColumns.containsKey(entry.getKey())) {
                entries.add(new Entry(entry.getKey(), "column " + entry.getValue().label(), Side.RIGHT));
            }
        }
    }

    private static Map<StableId, String> tableNamesById(Collection<TableModel> tables) {
        Map<StableId, String> names = new LinkedHashMap<>();
        for (TableModel table : tables) {
            names.put(table.id(), table.name());
        }
        return names;
    }

    private static Map<StableId, ColumnRef> columnsById(Collection<TableModel> tables) {
        Map<StableId, ColumnRef> columns = new LinkedHashMap<>();
        for (TableModel table : tables) {
            for (ColumnModel column : table.columns()) {
                columns.put(column.id(), new ColumnRef(table.name() + "." + column.name(), signature(column)));
            }
        }
        return columns;
    }

    private static String signature(ColumnModel column) {
        return column.nativeType() + (column.nullable() ? "" : " NOT NULL")
                + (column.defaultValue() == null ? "" : " DEFAULT " + column.defaultValue());
    }

    private record ColumnRef(String label, String signature) {
    }

    private SchemaDiff() {
    }
}
