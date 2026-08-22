package org.example.unit.adapters.mysql;

import org.example.core.replayer.SchemaOperation;
import org.example.adapters.mysql.MySqlDdlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MySqlDdlParser's one difference from PostgresDdlParser/H2DdlParser - see {@code org.example.adapters.SqlDdlParser}
 * for the shared CREATE TABLE/ADD|DROP|RENAME COLUMN/constraint/index logic every dialect's parser inherits
 * unchanged, already covered end-to-end via {@code PostgresDdlParserTest}. These two sanity checks confirm that
 * shared logic really is reachable through this subclass too, not just the retype spelling.
 */
class MySqlDdlParserTest {
    private final MySqlDdlParser parser = new MySqlDdlParser();

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
}
