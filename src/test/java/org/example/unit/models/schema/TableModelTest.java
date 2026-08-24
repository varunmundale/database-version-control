package org.example.unit.models.schema;

import org.example.models.schema.ColumnModel;
import org.example.models.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableModelTest {

    @Test
    void renamedToKeepsTheIdAndEveryColumnConstraintAndIndexId() {
        TableModel orders = TableModel.create("public", "orders",
                List.of(ColumnModel.unassigned("id", "INT", false, null)))
                .addConstraint("orders_pkey", org.example.models.schema.ConstraintType.PRIMARY_KEY, List.of("id"), null, List.of())
                .createIndex("idx_orders_id", true, List.of("id"));

        TableModel purchases = orders.renamedTo("purchases");

        assertEquals("purchases", purchases.name());
        assertEquals(orders.id(), purchases.id());
        assertEquals(orders.columns().getFirst().id(), purchases.columns().getFirst().id());
        assertEquals(orders.constraints().getFirst().id(), purchases.constraints().getFirst().id());
        assertEquals(orders.indexes().getFirst().id(), purchases.indexes().getFirst().id());
    }

    @Test
    void aTableDiffersFromAnotherOnlyWhenItsNameDiffers() {
        TableModel orders = TableModel.create("public", "orders",
                List.of(ColumnModel.unassigned("id", "INT", false, null)));
        TableModel ordersWithMoreColumns = orders.addColumn(ColumnModel.unassigned("total", "INT", true, null));
        TableModel purchases = orders.renamedTo("purchases");

        assertFalse(orders.differsFrom(ordersWithMoreColumns), "adding a column is not a table-level change");
        assertTrue(orders.differsFrom(purchases), "a rename is a table-level change");
    }
}
