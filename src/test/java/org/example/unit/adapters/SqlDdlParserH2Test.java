package org.example.unit.adapters;

import org.example.adapters.DialectGrammar;
import org.example.adapters.SqlDdlParser;
import org.example.core.replayer.SchemaOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SqlDdlParser} configured with {@link DialectGrammar#h2()}, which accepts both identity spellings. */
class SqlDdlParserH2Test {
    private final SqlDdlParser parser = new SqlDdlParser(DialectGrammar.h2());

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

    /** H2 accepts both Postgres's identity spelling and MySQL's, unlike either dialect's own strict grammar. */
    @Test
    void parsesBothPostgresAndMySqlsIdentitySpellings() {
        SchemaOperation.CreateTable generated = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT GENERATED ALWAYS AS IDENTITY)");
        assertEquals("GENERATED ALWAYS AS IDENTITY", generated.columns().getFirst().generatedAs());

        SchemaOperation.CreateTable autoIncrement = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT AUTO_INCREMENT)");
        assertEquals("AUTO_INCREMENT", autoIncrement.columns().getFirst().generatedAs());
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
}
