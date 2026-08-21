package org.example.core.differ;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HistoryDiffFormatterTest {
    private final HistoryDiffFormatter formatter = new HistoryDiffFormatter();
    private long idSequence = 1;

    @Test
    void identicalHistoriesProduceNoLines() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY);");

        List<String> lines = formatter.format("left", "right", List.of(create), List.of(create));

        assertEquals(List.of(), lines);
    }

    @Test
    void aColumnChangedOnlyOnOneSideIsNotLabeledAsAConflict() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY);");
        ChangeSet addColumn = changeset("ALTER TABLE orders ADD COLUMN total INT;");

        List<String> lines = formatter.format("main", "feature/orders", List.of(create, addColumn), List.of(create));

        assertEquals(List.of(
                "main vs feature/orders",
                "- orders",
                "  |- total",
                "    |- > ALTER TABLE orders ADD COLUMN total INT;"
        ), lines);
    }

    @Test
    void bothSidesAddingDifferentColumnsToTheSameTableIsNotAConflictAccordingToDatabaseDiff() {
        // Same table, but no shared object actually disagrees - each column gets its own node, neither labeled.
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY);");
        ChangeSet leftAdd = changeset("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        ChangeSet rightAdd = changeset("ALTER TABLE orders ADD COLUMN note TEXT;");

        List<String> lines = formatter.format("left", "right", List.of(create, leftAdd), List.of(create, rightAdd));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- note",
                "    |- < ALTER TABLE orders ADD COLUMN note TEXT;",
                "  |- total",
                "    |- > ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);"
        ), lines);
    }

    @Test
    void aConflictingColumnIsLabeledAndListsBothSidesStatementsUnderneathIt() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");
        ChangeSet leftAlter = changeset("ALTER TABLE orders ALTER COLUMN total TYPE INT;");
        ChangeSet rightAlter = changeset("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");

        List<String> lines = formatter.format("left", "right", List.of(create, leftAlter), List.of(create, rightAlter));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- total (conflicting)",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE INT;",
                "    |- < ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;"
        ), lines);
    }

    @Test
    void renamingAColumnOnOneSideWhileTheOtherModifiesItIsAConflictByStableIdEvenThoughTheNamesDiffer() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY, col1 NUMERIC(10,2));");
        ChangeSet rename = changeset("ALTER TABLE orders RENAME COLUMN col1 TO col2;");
        ChangeSet retype = changeset("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;");

        List<String> lines = formatter.format("left", "right", List.of(create, rename), List.of(create, retype));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- col2 (conflicting)",
                "    |- > ALTER TABLE orders RENAME COLUMN col1 TO col2;",
                "    |- < ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;"
        ), lines);
    }

    @Test
    void multipleStatementsAgainstTheSameConflictingColumnOnOneSideAreAllListed() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");
        ChangeSet leftAlter1 = changeset("ALTER TABLE orders ALTER COLUMN total TYPE INT;");
        ChangeSet leftAlter2 = changeset("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");
        ChangeSet rightAlter = changeset("ALTER TABLE orders ALTER COLUMN total TYPE NUMERIC(12,4);");

        List<String> lines = formatter.format("left", "right",
                List.of(create, leftAlter1, leftAlter2), List.of(create, rightAlter));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- total (conflicting)",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE INT;",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;",
                "    |- < ALTER TABLE orders ALTER COLUMN total TYPE NUMERIC(12,4);"
        ), lines);
    }

    @Test
    void changesBeforeTheCommonAncestorAreExcludedFromTheOutput() {
        ChangeSet create = changeset("CREATE TABLE orders (id INT PRIMARY KEY);");
        ChangeSet divergentLeft = changeset("ALTER TABLE orders ADD COLUMN total INT;");

        List<String> lines = formatter.format("left", "right", List.of(create, divergentLeft), List.of(create));

        assertEquals(0, lines.stream().filter(line -> line.contains(create.ddl())).count());
        assertEquals(1, lines.stream().filter(line -> line.contains(divergentLeft.ddl())).count());
    }

    private ChangeSet changeset(String ddl) {
        return new ChangeSet(idSequence++, "test", ddl, ChangesetStatus.COMMIT, Instant.now());
    }
}
