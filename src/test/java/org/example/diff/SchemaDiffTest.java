package org.example.diff;

import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;
import org.example.replay.SchemaReplayer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.example.diff.SchemaDiff.Side.CONFLICT;
import static org.example.diff.SchemaDiff.Side.LEFT;
import static org.example.diff.SchemaDiff.Side.RIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffTest {
    private final SchemaReplayer replayer = new SchemaReplayer();
    private long idSequence = 1;

    @Test
    void identicalSchemasHaveNoDifferences() {
        TableModel orders = table("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(List.of(orders), List.of(orders));

        assertTrue(entries.isEmpty());
    }

    @Test
    void tableOnlyOnLeftReportsTheTableAndEachColumnAsLeft() {
        TableModel orders = table("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(List.of(orders), List.of());

        assertEquals(3, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.side() == LEFT));
        assertTrue(entries.stream().anyMatch(entry -> entry.description().equals("table orders")));
        assertTrue(entries.stream().anyMatch(entry -> entry.description().equals("column orders.id")));
        assertTrue(entries.stream().anyMatch(entry -> entry.description().equals("column orders.total")));
    }

    @Test
    void tableOnlyOnRightReportsTheTableAndEachColumnAsRight() {
        TableModel orders = table("CREATE TABLE orders (id INT PRIMARY KEY);");

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(List.of(), List.of(orders));

        assertEquals(2, entries.size());
        assertTrue(entries.stream().allMatch(entry -> entry.side() == RIGHT));
    }

    @Test
    void aColumnChangedOnBothSidesIsAConflictButTheUnchangedTableIsNot() {
        TableModel left = table("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");
        TableModel right = table("CREATE TABLE orders (id INT PRIMARY KEY);",
                "ALTER TABLE orders ADD COLUMN total INT NOT NULL;");
        // right's 'total' has a different type than left's - same stable id, different definition.

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(List.of(left), List.of(right));

        assertEquals(1, entries.size());
        assertEquals(CONFLICT, entries.getFirst().side());
        assertTrue(entries.getFirst().description().startsWith("column orders.total"));
    }

    @Test
    void anAddedColumnOnOneSideIsNotAConflictAndDoesNotFlagTheTable() {
        TableModel left = table("CREATE TABLE orders (id INT PRIMARY KEY, total NUMERIC(10,2));");
        TableModel right = table("CREATE TABLE orders (id INT PRIMARY KEY);");

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(List.of(left), List.of(right));

        assertEquals(1, entries.size());
        assertEquals(LEFT, entries.getFirst().side());
        assertEquals("column orders.total", entries.getFirst().description());
    }

    @Test
    void renamingAColumnOnOneSideWhileTheOtherSideModifiesItIsAConflictNotAnUnrelatedAppearanceAndDisappearance() {
        String createOrders = "CREATE TABLE orders (id INT PRIMARY KEY, col1 NUMERIC(10,2));";
        Map<String, TableModel> renamedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders RENAME COLUMN col1 TO col2;")));
        Map<String, TableModel> modifiedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;")));

        List<SchemaDiff.Entry> entries = SchemaDiff.diff(renamedSide.values(), modifiedSide.values());

        assertEquals(1, entries.size());
        assertEquals(CONFLICT, entries.getFirst().side());
        assertTrue(entries.getFirst().description().contains("col2"));
        assertTrue(entries.getFirst().description().contains("col1"));
    }

    private ChangeSet changeset(String ddl) {
        return new ChangeSet(idSequence++, "test", ddl, ChangesetStatus.COMMIT, Instant.now());
    }

    /** Builds a fixture TableModel by replaying one or more DDL statements, in order, through the same replay engine DbGitService uses. */
    private TableModel table(String... ddls) {
        List<ChangeSet> changesets = new ArrayList<>();
        long id = 1;
        for (String ddl : ddls) {
            changesets.add(new ChangeSet(id++, "test", ddl, ChangesetStatus.COMMIT, Instant.now()));
        }
        return replayer.replay(changesets).values().iterator().next();
    }
}
