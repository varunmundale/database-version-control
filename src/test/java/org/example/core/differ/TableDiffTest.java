package org.example.core.differ;

import org.example.core.replayer.Replayer;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers what one table pairing works out on its own, without going through {@link DatabaseDiff}'s table matching. */
class TableDiffTest {
    private final Replayer replayer = new Replayer();

    @Test
    void aTablePresentOnBothSidesAndUnchangedHasNothingToReport() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");

        TableDiff tableDiff = TableDiff.between("orders", orders, orders);

        assertTrue(tableDiff.isEmpty());
        assertEquals(Side.CONFLICT, tableDiff.side());
        assertTrue(tableDiff.columnDiffs().isEmpty());
    }

    @Test
    void aMissingSideReportsEveryColumnOfTheSideThatExists() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");

        TableDiff leftOnly = TableDiff.between("orders", orders, null);

        assertTrue(leftOnly.onlyOnLeft());
        assertFalse(leftOnly.isEmpty());
        assertEquals(List.of("id", "total"), leftOnly.columnDiffs().stream().map(ColumnDiff::columnName).toList());
        assertTrue(leftOnly.columnDiffs().stream().allMatch(column -> column.side() == Side.LEFT));
    }

    @Test
    void reportsOnlyTheColumnsThatActuallyDiffer() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));",
                "ALTER TABLE orders ADD COLUMN placed_at TIMESTAMPTZ;");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertEquals(List.of("placed_at"), tableDiff.columnDiffs().stream().map(ColumnDiff::columnName).toList());
        assertEquals(Side.RIGHT, tableDiff.columnDiffs().getFirst().side());
    }

    @Test
    void matchesColumnsByStableIdSoARenameIsNotTwoSeparateColumns() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));",
                "ALTER TABLE orders RENAME COLUMN total TO amount;");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertEquals(1, tableDiff.columnDiffs().size(), "a rename is one column that differs, not a drop plus an add");
        ColumnDiff columnDiff = tableDiff.columnDiffs().getFirst();
        assertTrue(columnDiff.isRename());
        assertEquals(Side.CONFLICT, columnDiff.side());
    }

    @Test
    void ordersColumnsByName() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);",
                "ALTER TABLE orders ADD COLUMN zebra INT;",
                "ALTER TABLE orders ADD COLUMN alpha INT;");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertEquals(List.of("alpha", "zebra"), tableDiff.columnDiffs().stream().map(ColumnDiff::columnName).toList());
    }

    private TableModel table(String... ddls) {
        List<ChangeSet> changesets = new ArrayList<>();
        long id = 1;
        for (String ddl : ddls) {
            changesets.add(new ChangeSet(id++, "test", ddl, ChangesetStatus.COMMIT, Instant.now()));
        }
        return replayer.replay(changesets).values().iterator().next();
    }

    @Test
    void reportsAConstraintOnlyOneSideHas() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL);",
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertTrue(tableDiff.columnDiffs().isEmpty(), "the column itself is unchanged");
        assertEquals(List.of("orders_pkey"),
                tableDiff.constraintDiffs().stream().map(ConstraintDiff::constraintName).toList());
        assertEquals(Side.LEFT, tableDiff.constraintDiffs().getFirst().side());
        assertFalse(tableDiff.isEmpty(), "a constraint difference alone is worth reporting");
    }

    @Test
    void aConstraintOfTheSameNameDefinedDifferentlyOnEachSideConflicts() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, email TEXT);",
                "ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (id);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL, email TEXT);",
                "ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (email);");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertEquals(1, tableDiff.constraintDiffs().size());
        assertEquals(Side.CONFLICT, tableDiff.constraintDiffs().getFirst().side());
    }

    @Test
    void anIdenticalConstraintOnBothSidesIsNotReported() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL);",
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);",
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);");

        assertTrue(TableDiff.between("orders", left, right).isEmpty());
    }

    @Test
    void reportsIndexesIncludingOneThatDiffersOnlyInUniqueness() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total INT);",
                "CREATE INDEX idx_orders_total ON orders (total);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL, total INT);",
                "CREATE UNIQUE INDEX idx_orders_total ON orders (total);");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertEquals(List.of("idx_orders_total"), tableDiff.indexDiffs().stream().map(IndexDiff::indexName).toList());
        assertEquals(Side.CONFLICT, tableDiff.indexDiffs().getFirst().side());
        assertFalse(tableDiff.indexDiffs().getFirst().left().unique());
        assertTrue(tableDiff.indexDiffs().getFirst().right().unique());
    }

    @Test
    void anIndexIsUnchangedByRenamingTheColumnItCovers() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total INT);",
                "CREATE INDEX idx_orders_total ON orders (total);",
                "ALTER TABLE orders RENAME COLUMN total TO amount;");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL, total INT);",
                "CREATE INDEX idx_orders_total ON orders (total);");

        TableDiff tableDiff = TableDiff.between("orders", left, right);

        assertTrue(tableDiff.indexDiffs().isEmpty(), "the index still covers the same column, by stable id");
        assertEquals(List.of("amount"), tableDiff.columnDiffs().stream().map(ColumnDiff::columnName).toList());
    }

    @Test
    void aMissingSideReportsItsConstraintsAndIndexesToo() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total INT);",
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);",
                "CREATE INDEX idx_orders_total ON orders (total);");

        TableDiff leftOnly = TableDiff.between("orders", orders, null);

        assertEquals(1, leftOnly.constraintDiffs().size());
        assertEquals(1, leftOnly.indexDiffs().size());
        assertEquals(Side.LEFT, leftOnly.constraintDiffs().getFirst().side());
        assertEquals(Side.LEFT, leftOnly.indexDiffs().getFirst().side());
    }
}
