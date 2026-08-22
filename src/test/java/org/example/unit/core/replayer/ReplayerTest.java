package org.example.unit.core.replayer;


import org.example.core.replayer.Replayer;
import org.example.models.schema.ColumnModel;
import org.example.models.schema.TableModel;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayerTest {
    private final Replayer replayer = new Replayer();
    private long nextId = 1;

    @Test
    void renamingAColumnPreservesItsStableIdUnderTheNewName() {
        List<ChangeSet> history = List.of(
                changeset("CREATE TABLE orders (id INT NOT NULL, note TEXT);"),
                changeset("ALTER TABLE orders RENAME COLUMN note TO memo;"));

        Map<String, TableModel> before = replayer.replay(List.of(history.get(0)));
        Map<String, TableModel> after = replayer.replay(history);

        ColumnModel originalNote = column(before.get("orders"), "note");
        ColumnModel renamedMemo = column(after.get("orders"), "memo");
        assertEquals(originalNote.id(), renamedMemo.id());
        assertTrue(after.get("orders").columns().stream().noneMatch(c -> c.name().equals("note")));
    }

    @Test
    void aColumnDroppedAndADifferentlyNamedColumnAddedAreNotTreatedAsARename() {
        List<ChangeSet> history = List.of(
                changeset("CREATE TABLE orders (id INT NOT NULL, note TEXT);"),
                changeset("ALTER TABLE orders DROP COLUMN note;"),
                changeset("ALTER TABLE orders ADD COLUMN memo TEXT;"));

        Map<String, TableModel> before = replayer.replay(List.of(history.get(0)));
        Map<String, TableModel> after = replayer.replay(history);

        ColumnModel originalNote = column(before.get("orders"), "note");
        ColumnModel newMemo = column(after.get("orders"), "memo");
        assertNotEquals(originalNote.id(), newMemo.id());
    }

    @Test
    void alterColumnTypeKeepsTheSameNameAndStableId() {
        List<ChangeSet> history = List.of(
                changeset("CREATE TABLE orders (id INT NOT NULL, col1 NUMERIC(10,2));"),
                changeset("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;"));

        Map<String, TableModel> before = replayer.replay(List.of(history.get(0)));
        Map<String, TableModel> after = replayer.replay(history);

        ColumnModel original = column(before.get("orders"), "col1");
        ColumnModel updated = column(after.get("orders"), "col1");
        assertEquals(original.id(), updated.id());
        assertEquals("BIGINT", updated.nativeType());
    }

    @Test
    void renameOnOneBranchAndModificationOnTheOtherShareTheSameStableIdAfterDivergence() {
        String createOrders = "CREATE TABLE orders (id INT NOT NULL, col1 NUMERIC(10,2));";

        Map<String, TableModel> renamedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders RENAME COLUMN col1 TO col2;")));
        Map<String, TableModel> modifiedSide = replayer.replay(List.of(
                changeset(createOrders), changeset("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;")));

        // Each branch independently replays its own full history from CREATE TABLE onward; the shared prefix
        // deterministically produces the same stable id on both sides, since a column's id depends only on the
        // DDL sequence, not on which branch replayed it.
        ColumnModel col2 = column(renamedSide.get("orders"), "col2");
        ColumnModel col1 = column(modifiedSide.get("orders"), "col1");
        assertEquals(col1.id(), col2.id());
    }

    private static ColumnModel column(TableModel table, String name) {
        return table.columns().stream().filter(column -> column.name().equals(name)).findFirst().orElseThrow();
    }

    private ChangeSet changeset(String ddl) {
        return new ChangeSet(nextId++, "test", ddl, ChangesetStatus.COMMIT, Instant.now());
    }
}
