package org.example.core;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaOperationApplierTest {
    private final SchemaOperationApplier applier = new SchemaOperationApplier();

    @Test
    void createTableBuildsColumnsInOrderWithFreshStableIds() {
        SchemaOperation.CreateTable op = new SchemaOperation.CreateTable("orders", false, List.of(
                ColumnModel.unassigned("id", "INT", false, null),
                ColumnModel.unassigned("total", "NUMERIC(10,2)", true, null)));

        TableModel table = applier.apply("public", op, null);

        assertEquals("orders", table.name());
        assertEquals("public", table.schema());
        assertEquals(List.of("id", "total"), table.columns().stream().map(ColumnModel::name).toList());
    }

    @Test
    void createTableIfNotExistsReturnsTheExistingTableUnchanged() {
        TableModel existing = applier.apply("public", new SchemaOperation.CreateTable("orders", false,
                List.of(ColumnModel.unassigned("id", "INT", false, null))), null);

        TableModel result = applier.apply("public",
                new SchemaOperation.CreateTable("orders", true, List.of()), existing);

        assertSame(existing, result);
    }

    @Test
    void createTableWithoutIfNotExistsOnAnExistingTableThrows() {
        TableModel existing = applier.apply("public", new SchemaOperation.CreateTable("orders", false,
                List.of(ColumnModel.unassigned("id", "INT", false, null))), null);

        assertThrows(IllegalArgumentException.class, () -> applier.apply("public",
                new SchemaOperation.CreateTable("orders", false, List.of()), existing));
    }

    @Test
    void addColumnGivesTheNewColumnAFreshStableIdAndAppendsIt() {
        TableModel existing = table("id");

        TableModel updated = applier.apply("public", new SchemaOperation.AddColumn("orders",
                ColumnModel.unassigned("total", "NUMERIC(10,2)", true, null)), existing);

        assertEquals(List.of("id", "total"), updated.columns().stream().map(ColumnModel::name).toList());
    }

    @Test
    void addColumnOnAnExistingNameThrows() {
        TableModel existing = table("id");

        assertThrows(IllegalArgumentException.class, () -> applier.apply("public",
                new SchemaOperation.AddColumn("orders", ColumnModel.unassigned("id", "INT", false, null)), existing));
    }

    @Test
    void dropColumnRemovesItAndLeavesOthersUntouched() {
        TableModel existing = table("id", "note");

        TableModel updated = applier.apply("public", new SchemaOperation.DropColumn("orders", "note"), existing);

        assertEquals(1, updated.columns().size());
        assertEquals("id", updated.columns().getFirst().name());
    }

    @Test
    void dropColumnOnAnUnknownColumnThrows() {
        TableModel existing = table("id");

        assertThrows(IllegalArgumentException.class,
                () -> applier.apply("public", new SchemaOperation.DropColumn("orders", "missing"), existing));
    }

    @Test
    void renameColumnPreservesTheStableIdTypeAndNullability() {
        TableModel existing = table("id", "note");
        ColumnModel note = column(existing, "note");

        TableModel updated = applier.apply("public", new SchemaOperation.RenameColumn("orders", "note", "memo"), existing);

        ColumnModel memo = column(updated, "memo");
        assertEquals(note.id(), memo.id());
        assertEquals(note.nativeType(), memo.nativeType());
        assertEquals(note.nullable(), memo.nullable());
        assertTrue(updated.columns().stream().noneMatch(column -> column.name().equals("note")));
    }

    @Test
    void renameColumnOntoAnExistingNameThrows() {
        TableModel existing = table("id", "note");

        assertThrows(IllegalArgumentException.class,
                () -> applier.apply("public", new SchemaOperation.RenameColumn("orders", "note", "id"), existing));
    }

    @Test
    void alterColumnTypeChangesTheTypeButKeepsIdAndName() {
        TableModel existing = table("id", "col1");
        ColumnModel col1 = column(existing, "col1");

        TableModel updated = applier.apply("public", new SchemaOperation.AlterColumnType("orders", "col1", "BIGINT"), existing);

        ColumnModel updatedCol1 = column(updated, "col1");
        assertEquals(col1.id(), updatedCol1.id());
        assertEquals("BIGINT", updatedCol1.nativeType());
    }

    @Test
    void operationsOnAnUnknownTableThrow() {
        assertThrows(IllegalArgumentException.class,
                () -> applier.apply("public", new SchemaOperation.AddColumn("orders", ColumnModel.unassigned("a", "INT", true, null)), null));
    }

    private TableModel table(String... columnNames) {
        List<ColumnModel> columns = java.util.Arrays.stream(columnNames)
                .map(name -> ColumnModel.unassigned(name, "INT", true, null))
                .toList();
        return applier.apply("public", new SchemaOperation.CreateTable("orders", false, columns), null);
    }

    private static ColumnModel column(TableModel table, String name) {
        return table.columns().stream().filter(column -> column.name().equals(name)).findFirst().orElseThrow();
    }
}
