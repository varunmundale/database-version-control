package org.example.unit.adapters.h2;

import org.example.core.replayer.SchemaOperation;
import org.example.adapters.h2.H2DdlParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H2DdlParser accepts the same retype spelling PostgresDdlParser does - H2 understands
 * {@code ALTER COLUMN ... TYPE} directly - and rejects MySQL's {@code MODIFY COLUMN}, the reverse of
 * {@code MySqlDdlParserTest}. See {@code org.example.adapters.SqlDdlParser} for the shared CREATE TABLE/
 * ADD|DROP|RENAME COLUMN/constraint/index logic every dialect's parser inherits unchanged, already covered
 * end-to-end via {@code PostgresDdlParserTest}.
 */
class H2DdlParserTest {
    private final H2DdlParser parser = new H2DdlParser();

    @Test
    void parsesCreateTableTheSameWayEveryDialectDoes() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2))");

        assertEquals("orders", operation.tableName());
        assertEquals(2, operation.columns().size());
    }

    @Test
    void parsesAlterColumnTypeAsARetype() {
        SchemaOperation.AlterColumnType operation = (SchemaOperation.AlterColumnType) parser.parse(
                "ALTER TABLE orders ALTER COLUMN total TYPE BIGINT");

        assertEquals("orders", operation.tableName());
        assertEquals("total", operation.columnName());
        assertEquals("BIGINT", operation.newType());
    }

    /** MySQL's retype spelling - not Postgres/H2's. */
    @Test
    void rejectsMySqlsModifyColumnSpelling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("ALTER TABLE orders MODIFY COLUMN total BIGINT"));

        assertTrue(exception.getMessage().contains("ALTER COLUMN"), exception.getMessage());
    }
}
