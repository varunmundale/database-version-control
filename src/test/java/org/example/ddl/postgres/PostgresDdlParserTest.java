package org.example.ddl.postgres;

import org.example.ddl.ColumnDefinition;
import org.example.ddl.SchemaOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresDdlParserTest {
    private final PostgresDdlParser parser = new PostgresDdlParser();

    @Test
    void parsesCreateTableIntoAllItsColumnDefinitions() {
        String ddl = """
                CREATE TABLE orders (
                    id INT PRIMARY KEY,
                    total NUMERIC(10,2) NOT NULL DEFAULT 0,
                    note TEXT
                )""";

        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(ddl);

        assertEquals("orders", operation.tableName());
        assertFalse(operation.ifNotExists());
        assertEquals(3, operation.columns().size());

        ColumnDefinition id = operation.columns().get(0);
        assertEquals("INT", id.nativeType());
        assertFalse(id.nullable());
        assertNull(id.defaultValue());

        ColumnDefinition total = operation.columns().get(1);
        assertEquals("NUMERIC(10,2)", total.nativeType());
        assertFalse(total.nullable());
        assertEquals("0", total.defaultValue());

        ColumnDefinition note = operation.columns().get(2);
        assertEquals("TEXT", note.nativeType());
        assertTrue(note.nullable());
    }

    @Test
    void parsesCreateTableIfNotExistsAndSchemaQualifiedNames() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE IF NOT EXISTS public.orders (id INT PRIMARY KEY)");

        assertEquals("orders", operation.tableName());
        assertTrue(operation.ifNotExists());
    }

    @Test
    void ignoresTableLevelConstraintsWhenParsingColumns() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT, name TEXT, PRIMARY KEY(id), CONSTRAINT uq UNIQUE(name))");

        assertEquals(2, operation.columns().size());
    }

    @Test
    void doesNotMangleMultiWordTypeNames() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (placed_at TIMESTAMP WITH TIME ZONE)");

        assertEquals("TIMESTAMP WITH TIME ZONE", operation.columns().getFirst().nativeType());
    }

    @Test
    void parsesAddColumn() {
        SchemaOperation.AddColumn operation = (SchemaOperation.AddColumn) parser.parse(
                "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2) NOT NULL");

        assertEquals("orders", operation.tableName());
        assertEquals("total", operation.column().name());
        assertEquals("NUMERIC(10,2)", operation.column().nativeType());
        assertFalse(operation.column().nullable());
    }

    @Test
    void parsesDropColumn() {
        SchemaOperation.DropColumn operation = (SchemaOperation.DropColumn) parser.parse("ALTER TABLE orders DROP COLUMN note");

        assertEquals("orders", operation.tableName());
        assertEquals("note", operation.columnName());
    }

    @Test
    void parsesRenameColumn() {
        SchemaOperation.RenameColumn operation = (SchemaOperation.RenameColumn) parser.parse(
                "ALTER TABLE orders RENAME COLUMN note TO memo");

        assertEquals("orders", operation.tableName());
        assertEquals("note", operation.oldName());
        assertEquals("memo", operation.newName());
    }

    @Test
    void parsesAlterColumnType() {
        SchemaOperation.AlterColumnType operation = (SchemaOperation.AlterColumnType) parser.parse(
                "ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT");

        assertEquals("orders", operation.tableName());
        assertEquals("col1", operation.columnName());
        assertEquals("BIGINT", operation.newType());
    }

    @Test
    void rejectsAlterColumnVariantsThatAreNotATypeChange() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ALTER TABLE orders ALTER COLUMN col1 SET NOT NULL"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ALTER TABLE orders ALTER COLUMN col1 DROP NOT NULL"));
    }

    @Test
    void rejectsMultiClauseAlterStatements() {
        assertThrows(IllegalArgumentException.class,
                () -> parser.parse("ALTER TABLE orders ADD COLUMN a INT, ADD COLUMN b TEXT"));
    }

    @Test
    void rejectsUnsupportedStatements() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("DROP TABLE orders"));
    }

    @Test
    void rejectsUnparseableStatements() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("not even sql"));
    }
}
