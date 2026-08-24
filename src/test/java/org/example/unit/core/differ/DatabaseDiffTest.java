package org.example.unit.core.differ;


import org.example.core.differ.ColumnDiff;
import org.example.core.differ.DatabaseDiff;
import org.example.core.differ.Side;
import org.example.core.differ.TableDiff;
import org.example.core.replayer.Replayer;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDiffTest {
    private final Replayer replayer = new Replayer();
    private final DatabaseDiff databaseDiff = new DatabaseDiff();
    private long idSequence = 1;

    @Test
    void identicalSchemasHaveNoDifferences() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(orders), List.of(orders));

        assertTrue(tableDiffs.isEmpty());
    }

    @Test
    void tableOnlyOnLeftReportsTheTableAndEachColumnAsLeft() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(orders), List.of());

        assertEquals(1, tableDiffs.size());
        TableDiff tableDiff = tableDiffs.getFirst();
        assertEquals("orders", tableDiff.tableName());
        assertTrue(tableDiff.onlyOnLeft());
        assertFalse(tableDiff.onlyOnRight());
        assertEquals(2, tableDiff.columnDiffs().size());
        assertTrue(tableDiff.columnDiffs().stream().allMatch(column -> column.side() == Side.LEFT));
        assertEquals(List.of("id", "total"), tableDiff.columnDiffs().stream().map(ColumnDiff::columnName).toList());
    }

    @Test
    void tableOnlyOnRightReportsTheTableAndEachColumnAsRight() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL);");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(), List.of(orders));

        TableDiff tableDiff = tableDiffs.getFirst();
        assertTrue(tableDiff.onlyOnRight());
        assertTrue(tableDiff.columnDiffs().stream().allMatch(column -> column.side() == Side.RIGHT));
    }

    @Test
    void aColumnChangedOnBothSidesIsAConflictButTheUnchangedTableIsNot() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);",
                "ALTER TABLE orders ADD COLUMN total INT NOT NULL;");
        // right's 'total' has a different type than left's - same stable id, different definition.

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(left), List.of(right));

        assertEquals(1, tableDiffs.size());
        TableDiff tableDiff = tableDiffs.getFirst();
        assertFalse(tableDiff.onlyOnLeft());
        assertFalse(tableDiff.onlyOnRight());
        assertEquals(1, tableDiff.columnDiffs().size());
        ColumnDiff columnDiff = tableDiff.columnDiffs().getFirst();
        assertEquals(Side.BOTH, columnDiff.side());
        assertEquals("total", columnDiff.columnName());
        assertFalse(columnDiff.isRename());
    }

    @Test
    void anAddedColumnOnOneSideIsNotAConflictAndDoesNotFlagTheTable() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(left), List.of(right));

        ColumnDiff columnDiff = tableDiffs.getFirst().columnDiffs().getFirst();
        assertEquals(Side.LEFT, columnDiff.side());
        assertEquals("total", columnDiff.columnName());
    }

    @Test
    void renamingAColumnOnOneSideWhileTheOtherSideModifiesItIsAConflictNotAnUnrelatedAppearanceAndDisappearance() {
        String createOrders = "CREATE TABLE orders (id INT NOT NULL, col1 NUMERIC(10,2));";
        Map<String, TableModel> renamedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders RENAME COLUMN col1 TO col2;")));
        Map<String, TableModel> modifiedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;")));

        List<TableDiff> tableDiffs = databaseDiff.diff(renamedSide.values(), modifiedSide.values());

        assertEquals(1, tableDiffs.size());
        ColumnDiff columnDiff = tableDiffs.getFirst().columnDiffs().getFirst();
        assertEquals(Side.BOTH, columnDiff.side());
        assertTrue(columnDiff.isRename());
        assertEquals("col2", columnDiff.left().name());
        assertEquals("col1", columnDiff.right().name());
        assertEquals(columnDiff.left().id(), columnDiff.right().id());
    }

    @Test
    void columnDiffsWithinATableAreOrderedByColumnName() {
        TableModel left = table("CREATE TABLE orders (id INT NOT NULL, zeta TEXT, alpha TEXT, mid TEXT);");
        TableModel right = table("CREATE TABLE orders (id INT NOT NULL);");
        // zeta, alpha and mid are each LEFT-only diffs; output should read alpha, mid, zeta - not creation order.

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(left), List.of(right));

        assertEquals(List.of("alpha", "mid", "zeta"),
                tableDiffs.getFirst().columnDiffs().stream().map(ColumnDiff::columnName).toList());
    }

    @Test
    void eachTableIsDiffedIndependentlyAndResultsAreOrderedByTableName() {
        TableModel zebras = table("CREATE TABLE zebras (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel accounts = table("CREATE TABLE accounts (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel zebrasChanged = table("CREATE TABLE zebras (id INT NOT NULL);", "ALTER TABLE zebras ADD COLUMN total INT NOT NULL;");
        TableModel accountsChanged = table("CREATE TABLE accounts (id INT NOT NULL);", "ALTER TABLE accounts ADD COLUMN total INT NOT NULL;");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(zebras, accounts), List.of(zebrasChanged, accountsChanged));

        // "accounts" sorts before "zebras" even though "zebras" was added to the input lists first.
        assertEquals(List.of("accounts", "zebras"), tableDiffs.stream().map(TableDiff::tableName).toList());
        assertEquals(2, tableDiffs.size());
        assertTrue(tableDiffs.stream().allMatch(t -> t.columnDiffs().size() == 1));
    }

    @Test
    void matchesTablesByStableIdSoARenameIsNotADropPlusAnAdd() {
        TableModel orders = table("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        TableModel purchases = orders.renamedTo("purchases");

        List<TableDiff> tableDiffs = databaseDiff.diff(List.of(orders), List.of(purchases));

        assertEquals(1, tableDiffs.size(), "one table that was renamed, not one dropped and a different one added");
        TableDiff tableDiff = tableDiffs.getFirst();
        assertEquals(Side.BOTH, tableDiff.side());
        assertTrue(tableDiff.columnDiffs().isEmpty(), "every column's id was carried over, so nothing inside differs");
    }

    private ChangeSet changeset(String ddl) {
        return new ChangeSet(idSequence++, "test", ddl, ChangesetStatus.COMMIT, Instant.now());
    }

    /** Builds a fixture TableModel by replaying one or more DDL statements, in order, through the same replay engine the daemon uses. */
    private TableModel table(String... ddls) {
        List<ChangeSet> changesets = new ArrayList<>();
        long id = 1;
        for (String ddl : ddls) {
            changesets.add(new ChangeSet(id++, "test", ddl, ChangesetStatus.COMMIT, Instant.now()));
        }
        return replayer.replay(changesets).values().iterator().next();
    }
}
