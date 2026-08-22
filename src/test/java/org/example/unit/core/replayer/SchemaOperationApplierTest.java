package org.example.unit.core.replayer;


import org.example.core.replayer.SchemaOperation;
import org.example.core.replayer.SchemaOperationApplier;
import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintModel;
import org.example.models.schema.ConstraintType;
import org.example.models.schema.IndexModel;
import org.example.models.schema.StableId;
import org.example.models.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void addConstraintResolvesTheColumnsItCoversToTheirStableIds() {
        TableModel orders = orders();

        TableModel withKey = applier.apply("public", new SchemaOperation.AddConstraint("orders", "orders_pkey",
                ConstraintType.PRIMARY_KEY, List.of("id"), null, List.of()), orders);

        assertEquals(1, withKey.constraints().size());
        ConstraintModel constraint = withKey.constraints().getFirst();
        assertEquals("orders_pkey", constraint.name());
        assertEquals(ConstraintType.PRIMARY_KEY, constraint.type());
        assertEquals(List.of(column(withKey, "id").id()), constraint.columnIds());
    }

    @Test
    void aForeignKeyRecordsWhatItReferencesWithoutThatTableHavingBeenBuiltYet() {
        TableModel withForeignKey = applier.apply("public", new SchemaOperation.AddConstraint("orders",
                "orders_customer_fkey", ConstraintType.FOREIGN_KEY, List.of("customer_id"),
                "customers", List.of("id")), orders());

        ConstraintModel constraint = withForeignKey.constraints().getFirst();
        assertEquals(ConstraintType.FOREIGN_KEY, constraint.type());
        assertEquals(1, constraint.referencedColumnIds().size());
        assertEquals(StableId.of("table", "public.customers"), constraint.referencedTableId());
    }

    @Test
    void addConstraintRejectsAnUnknownColumnAndADuplicateName() {
        TableModel withKey = applier.apply("public", new SchemaOperation.AddConstraint("orders", "orders_pkey",
                ConstraintType.PRIMARY_KEY, List.of("id"), null, List.of()), orders());

        assertThrows(IllegalArgumentException.class, () -> applier.apply("public",
                new SchemaOperation.AddConstraint("orders", "orders_nope_key", ConstraintType.UNIQUE,
                        List.of("nope"), null, List.of()), withKey));
        assertThrows(IllegalArgumentException.class, () -> applier.apply("public",
                new SchemaOperation.AddConstraint("orders", "orders_pkey", ConstraintType.UNIQUE,
                        List.of("total"), null, List.of()), withKey));
    }

    @Test
    void dropConstraintRemovesItAndRejectsOneThatWasNeverThere() {
        TableModel withKey = applier.apply("public", new SchemaOperation.AddConstraint("orders", "orders_pkey",
                ConstraintType.PRIMARY_KEY, List.of("id"), null, List.of()), orders());

        TableModel dropped = applier.apply("public", new SchemaOperation.DropConstraint("orders", "orders_pkey"), withKey);

        assertTrue(dropped.constraints().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> applier.apply("public",
                new SchemaOperation.DropConstraint("orders", "orders_pkey"), dropped));
    }

    @Test
    void createIndexRecordsUniquenessAndTheColumnsItCovers() {
        TableModel indexed = applier.apply("public",
                new SchemaOperation.CreateIndex("orders", "idx_orders_total", true, List.of("total")), orders());

        IndexModel index = indexed.indexes().getFirst();
        assertEquals("idx_orders_total", index.name());
        assertTrue(index.unique());
        assertEquals(List.of(column(indexed, "total").id()), index.columnIds());
    }

    @Test
    void anIndexKeepsPointingAtAColumnThatIsLaterRenamed() {
        TableModel indexed = applier.apply("public",
                new SchemaOperation.CreateIndex("orders", "idx_orders_total", false, List.of("total")), orders());

        TableModel renamed = applier.apply("public",
                new SchemaOperation.RenameColumn("orders", "total", "amount"), indexed);

        assertEquals(List.of(column(renamed, "amount").id()), renamed.indexes().getFirst().columnIds());
        assertFalse(renamed.indexes().getFirst().columnIds().isEmpty());
    }

    private TableModel orders() {
        return applier.apply("public", new SchemaOperation.CreateTable("orders", false, List.of(
                ColumnModel.unassigned("id", "INT", false, null),
                ColumnModel.unassigned("customer_id", "INT", true, null),
                ColumnModel.unassigned("total", "NUMERIC(10,2)", true, null))), null);
    }
}
