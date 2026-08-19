package org.example.adapters;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DdlStatementParserTest {
    private final DdlStatementParser parser = new DdlStatementParser();

    @Test
    void createTableBuildsColumnsWithStableIdsMatchingTheJdbcParserScheme() {
        String ddl = """
                CREATE TABLE orders (
                    id INT PRIMARY KEY,
                    total NUMERIC(10,2) NOT NULL DEFAULT 0,
                    note TEXT
                );""";

        TableModel table = parser.apply("public", ddl, null);

        assertEquals(StableId.of("table", "public.orders"), table.id());
        assertEquals("orders", table.name());
        assertEquals("public", table.schema());
        assertEquals(3, table.columns().size());

        ColumnModel id = table.columns().get(0);
        assertEquals(StableId.of("column", table.id().value() + ".id"), id.id());
        assertEquals(1, id.ordinal());
        assertEquals("INT", id.nativeType());
        assertFalse(id.nullable());
        assertNull(id.defaultValue());

        ColumnModel total = table.columns().get(1);
        assertEquals("NUMERIC(10,2)", total.nativeType());
        assertFalse(total.nullable());
        assertEquals("0", total.defaultValue());

        ColumnModel note = table.columns().get(2);
        assertEquals("TEXT", note.nativeType());
        assertTrue(note.nullable());
    }

    @Test
    void alterTableAddColumnAppendsToTheExistingInternalRepresentation() {
        TableModel existing = parser.apply("public", "CREATE TABLE orders (id INT PRIMARY KEY);", null);

        TableModel updated = parser.apply("public", "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL;", existing);

        assertEquals(existing.id(), updated.id());
        assertEquals(2, updated.columns().size());
        ColumnModel total = updated.columns().get(1);
        assertEquals("total", total.name());
        assertEquals(2, total.ordinal());
        assertEquals(StableId.of("column", existing.id().value() + ".total"), total.id());
        assertFalse(total.nullable());
    }

    @Test
    void alterTableDropColumnRemovesIt() {
        TableModel existing = parser.apply("public", "CREATE TABLE orders (id INT PRIMARY KEY, note TEXT);", null);

        TableModel updated = parser.apply("public", "ALTER TABLE orders DROP COLUMN note;", existing);

        assertEquals(1, updated.columns().size());
        assertEquals("id", updated.columns().getFirst().name());
    }

    @Test
    void alterTableOnAnUnknownTableThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.apply("public", "ALTER TABLE orders ADD COLUMN total NUMERIC;", null));
    }

    @Test
    void createTableIfNotExistsReturnsTheExistingRepresentationUnchanged() {
        TableModel existing = parser.apply("public", "CREATE TABLE orders (id INT PRIMARY KEY);", null);

        TableModel result = parser.apply("public", "CREATE TABLE IF NOT EXISTS orders (id INT PRIMARY KEY);", existing);

        assertSame(existing, result);
    }

    @Test
    void createTableWithoutIfNotExistsOnAnExistingTableThrows() {
        TableModel existing = parser.apply("public", "CREATE TABLE orders (id INT PRIMARY KEY);", null);

        assertThrows(IllegalArgumentException.class, () -> parser.apply("public", "CREATE TABLE orders (id INT PRIMARY KEY);", existing));
    }

    @Test
    void unsupportedStatementsThrow() {
        assertThrows(IllegalArgumentException.class, () -> parser.apply("public", "DROP TABLE orders;", null));
    }

    @Test
    void tableNameIsExtractedFromCreateAndAlterStatements() {
        assertEquals("orders", parser.tableName("CREATE TABLE orders (id INT PRIMARY KEY);"));
        assertEquals("orders", parser.tableName("ALTER TABLE orders ADD COLUMN total NUMERIC;"));
        assertEquals("orders", parser.tableName("CREATE TABLE public.orders (id INT PRIMARY KEY);"));
    }
}
