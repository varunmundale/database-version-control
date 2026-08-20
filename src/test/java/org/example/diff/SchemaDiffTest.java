package org.example.diff;

import org.example.ddl.DdlStatementParser;
import org.example.model.schema.TableModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.example.diff.SchemaDiff.Side.CONFLICT;
import static org.example.diff.SchemaDiff.Side.LEFT;
import static org.example.diff.SchemaDiff.Side.RIGHT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffTest {
    private final DdlStatementParser parser = new DdlStatementParser();

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
        TableModel right = parser.apply("public", "ALTER TABLE orders ADD COLUMN total INT NOT NULL;",
                table("CREATE TABLE orders (id INT PRIMARY KEY);"));
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

    private TableModel table(String ddl) {
        return parser.apply("public", ddl, null);
    }
}
