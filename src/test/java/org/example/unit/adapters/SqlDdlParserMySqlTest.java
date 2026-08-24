package org.example.unit.adapters;

import org.example.adapters.DialectGrammar;
import org.example.adapters.SqlDdlParser;
import org.example.core.replayer.SchemaOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SqlDdlParser} configured with {@link DialectGrammar#mysql()} - MySQL's one difference from Postgres/H2's
 * grammar. See {@code org.example.adapters.SqlDdlParser} for the shared CREATE TABLE/ADD|DROP|RENAME
 * COLUMN/constraint/index logic every dialect runs unchanged, already covered end-to-end via
 * {@link SqlDdlParserPostgresTest}. These sanity checks confirm that shared logic really is reachable with this
 * grammar too, not just the retype/identity spellings.
 */
class SqlDdlParserMySqlTest {
    private final SqlDdlParser parser = new SqlDdlParser(DialectGrammar.mysql());

    @Test
    void parsesCreateTableTheSameWayEveryDialectDoes() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2))");

        assertEquals("orders", operation.tableName());
        assertEquals(2, operation.columns().size());
    }

    @Test
    void parsesAddColumnTheSameWayEveryDialectDoes() {
        SchemaOperation.AddColumn operation = (SchemaOperation.AddColumn) parser.parse(
                "ALTER TABLE orders ADD COLUMN note TEXT");

        assertEquals("orders", operation.tableName());
        assertEquals("note", operation.column().name());
    }

    @Test
    void parsesModifyColumnAsARetype() {
        SchemaOperation.AlterColumnType operation = (SchemaOperation.AlterColumnType) parser.parse(
                "ALTER TABLE orders MODIFY COLUMN total BIGINT");

        assertEquals("orders", operation.tableName());
        assertEquals("total", operation.columnName());
        assertEquals("BIGINT", operation.newType());
    }

    /** Postgres/H2's retype spelling - not MySQL's. */
    @Test
    void rejectsPostgressAlterColumnTypeSpelling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT"));

        assertTrue(exception.getMessage().contains("MODIFY COLUMN"), exception.getMessage());
    }

    @Test
    void parsesAutoIncrement() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT NOT NULL AUTO_INCREMENT, total NUMERIC(10,2))");

        assertEquals("AUTO_INCREMENT", operation.columns().get(0).generatedAs());
        assertNull(operation.columns().get(1).generatedAs());
    }

    /** Postgres's identity spelling - not MySQL's. */
    @Test
    void rejectsPostgressGeneratedAsIdentitySpelling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("CREATE TABLE orders (id INT GENERATED ALWAYS AS IDENTITY)"));

        assertTrue(exception.getMessage().contains("declares GENERATED"), exception.getMessage());
    }

    @Test
    void parsesDropTableTheSameWayEveryDialectDoes() {
        SchemaOperation.DropTable operation = (SchemaOperation.DropTable) parser.parse("DROP TABLE orders");

        assertEquals("orders", operation.tableName());
    }

    @Test
    void parsesRenameToTheSameWayEveryDialectDoes() {
        SchemaOperation.RenameTable operation = (SchemaOperation.RenameTable) parser.parse(
                "ALTER TABLE orders RENAME TO purchases");

        assertEquals("orders", operation.tableName());
        assertEquals("purchases", operation.newName());
    }

    /** MySQL's own table-rename spelling - out of scope, since ALTER TABLE ... RENAME TO covers every dialect. */
    @Test
    void rejectsTheRenameTableSpelling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("RENAME TABLE orders TO purchases"));

        assertTrue(exception.getMessage().contains("ALTER TABLE"), exception.getMessage());
        assertTrue(exception.getMessage().contains("RENAME TO"), exception.getMessage());
    }
}
