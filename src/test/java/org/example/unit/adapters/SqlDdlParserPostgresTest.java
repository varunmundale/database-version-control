package org.example.unit.adapters;

import org.example.adapters.DialectGrammar;
import org.example.adapters.SqlDdlParser;
import org.example.core.replayer.SchemaOperation;
import org.example.models.schema.ColumnModel;
import org.example.models.schema.ConstraintType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SqlDdlParser} configured with {@link DialectGrammar#postgresql()} - Postgres's own grammar. */
class SqlDdlParserPostgresTest {
    private final SqlDdlParser parser = new SqlDdlParser(DialectGrammar.postgresql());

    @Test
    void parsesCreateTableIntoAllItsColumnModels() {
        String ddl = """
                CREATE TABLE orders (
                    id INT NOT NULL,
                    total NUMERIC(10,2) NOT NULL DEFAULT 0,
                    note TEXT
                )""";

        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(ddl);

        assertEquals("orders", operation.tableName());
        assertFalse(operation.ifNotExists());
        assertEquals(3, operation.columns().size());

        ColumnModel id = operation.columns().get(0);
        assertEquals("INT", id.nativeType());
        assertFalse(id.nullable());
        assertNull(id.defaultValue());

        ColumnModel total = operation.columns().get(1);
        assertEquals("NUMERIC(10,2)", total.nativeType());
        assertFalse(total.nullable());
        assertEquals("0", total.defaultValue());

        ColumnModel note = operation.columns().get(2);
        assertEquals("TEXT", note.nativeType());
        assertTrue(note.nullable());
    }

    @Test
    void parsesCreateTableIfNotExistsAndSchemaQualifiedNames() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE IF NOT EXISTS public.orders (id INT NOT NULL)");

        assertEquals("orders", operation.tableName());
        assertTrue(operation.ifNotExists());
    }

    @Test
    void rejectsTableLevelConstraintsInCreateTable() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> parser.parse(
                "CREATE TABLE orders (id INT, name TEXT, PRIMARY KEY(id), CONSTRAINT uq UNIQUE(name))"));

        assertTrue(exception.getMessage().contains("CREATE TABLE defines columns only"), exception.getMessage());
        assertTrue(exception.getMessage().contains("Add it separately"), exception.getMessage());
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
    void parsesAlterColumnTypeWithAUsingConversionClause() {
        SchemaOperation.AlterColumnType operation = (SchemaOperation.AlterColumnType) parser.parse(
                "ALTER TABLE employees ALTER COLUMN department TYPE integer USING department::integer");

        assertEquals("employees", operation.tableName());
        assertEquals("department", operation.columnName());
        assertEquals("integer", operation.newType());
    }

    /**
     * MySQL's retype spelling. Postgres's {@link DialectGrammar} is strict to Postgres's own grammar - see
     * {@link SqlDdlParserMySqlTest} for the same statement accepted by the parser configured for the dialect that
     * understands it.
     */
    @Test
    void rejectsMySqlsModifyColumnSpelling() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("ALTER TABLE orders MODIFY COLUMN col1 BIGINT"));
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

    @Test
    void rejectsAConstraintDeclaredOnAColumn() {
        IllegalArgumentException primaryKey = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("CREATE TABLE orders (id INT PRIMARY KEY)"));
        assertTrue(primaryKey.getMessage().contains("declares PRIMARY KEY"), primaryKey.getMessage());
        assertTrue(primaryKey.getMessage().contains("ADD CONSTRAINT orders_pkey PRIMARY KEY (id)"), primaryKey.getMessage());

        IllegalArgumentException unique = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("CREATE TABLE orders (email TEXT UNIQUE)"));
        assertTrue(unique.getMessage().contains("declares UNIQUE"), unique.getMessage());

        IllegalArgumentException references = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("CREATE TABLE orders (customer_id INT REFERENCES customers(id))"));
        assertTrue(references.getMessage().contains("FOREIGN KEY"), references.getMessage());
    }

    @Test
    void stillAcceptsNotNullAndDefaultOnAColumnBecauseTheyBelongToTheColumn() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2) DEFAULT 0, note TEXT NULL)");

        assertFalse(operation.columns().get(0).nullable());
        assertEquals("0", operation.columns().get(1).defaultValue());
        assertTrue(operation.columns().get(2).nullable());
    }

    @Test
    void parsesGeneratedAlwaysAsIdentity() {
        SchemaOperation.CreateTable operation = (SchemaOperation.CreateTable) parser.parse(
                "CREATE TABLE orders (id INT GENERATED ALWAYS AS IDENTITY, total NUMERIC(10,2))");

        ColumnModel id = operation.columns().get(0);
        assertEquals("GENERATED ALWAYS AS IDENTITY", id.generatedAs());
        assertNull(operation.columns().get(1).generatedAs());
    }

    @Test
    void parsesGeneratedByDefaultAsIdentityAlongsideNotNull() {
        SchemaOperation.AddColumn operation = (SchemaOperation.AddColumn) parser.parse(
                "ALTER TABLE orders ADD COLUMN id INT NOT NULL GENERATED BY DEFAULT AS IDENTITY");

        assertFalse(operation.column().nullable());
        assertEquals("GENERATED BY DEFAULT AS IDENTITY", operation.column().generatedAs());
    }

    /** MySQL's identity spelling - not Postgres's. */
    @Test
    void rejectsMySqlsAutoIncrementSpelling() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("CREATE TABLE orders (id INT AUTO_INCREMENT)"));

        assertTrue(exception.getMessage().contains("declares AUTO_INCREMENT"), exception.getMessage());
    }

    @Test
    void parsesAddPrimaryKeyAndUniqueConstraints() {
        SchemaOperation.AddConstraint primaryKey = (SchemaOperation.AddConstraint) parser.parse(
                "ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id)");
        assertEquals("orders", primaryKey.tableName());
        assertEquals("orders_pkey", primaryKey.constraintName());
        assertEquals(ConstraintType.PRIMARY_KEY, primaryKey.type());
        assertEquals(List.of("id"), primaryKey.columnNames());
        assertNull(primaryKey.referencedTableName());

        SchemaOperation.AddConstraint unique = (SchemaOperation.AddConstraint) parser.parse(
                "ALTER TABLE orders ADD CONSTRAINT orders_email_key UNIQUE (email, region)");
        assertEquals(ConstraintType.UNIQUE, unique.type());
        assertEquals(List.of("email", "region"), unique.columnNames());
    }

    @Test
    void parsesAForeignKeyIncludingWhatItReferences() {
        SchemaOperation.AddConstraint foreignKey = (SchemaOperation.AddConstraint) parser.parse(
                "ALTER TABLE orders ADD CONSTRAINT orders_customer_fkey FOREIGN KEY (customer_id)"
                        + " REFERENCES customers (id)");

        assertEquals(ConstraintType.FOREIGN_KEY, foreignKey.type());
        assertEquals(List.of("customer_id"), foreignKey.columnNames());
        assertEquals("customers", foreignKey.referencedTableName());
        assertEquals(List.of("id"), foreignKey.referencedColumnNames());
    }

    @Test
    void parsesDropConstraintRatherThanReadingItAsADroppedColumn() {
        SchemaOperation.DropConstraint operation = (SchemaOperation.DropConstraint) parser.parse(
                "ALTER TABLE orders DROP CONSTRAINT orders_pkey");

        assertEquals("orders", operation.tableName());
        assertEquals("orders_pkey", operation.constraintName());
    }

    @Test
    void parsesPlainAndUniqueIndexes() {
        SchemaOperation.CreateIndex plain = (SchemaOperation.CreateIndex) parser.parse(
                "CREATE INDEX idx_orders_total ON orders (total)");
        assertEquals("orders", plain.tableName());
        assertEquals("idx_orders_total", plain.indexName());
        assertFalse(plain.unique());
        assertEquals(List.of("total"), plain.columnNames());

        SchemaOperation.CreateIndex unique = (SchemaOperation.CreateIndex) parser.parse(
                "CREATE UNIQUE INDEX idx_orders_email ON orders (email, region)");
        assertTrue(unique.unique());
        assertEquals(List.of("email", "region"), unique.columnNames());
    }

    @Test
    void rejectsConstraintKindsTheModelCannotHold() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("ALTER TABLE orders ADD CONSTRAINT positive CHECK (id > 0)"));

        assertTrue(exception.getMessage().contains("Unsupported constraint type"), exception.getMessage());
        assertTrue(exception.getMessage().contains("PRIMARY KEY, UNIQUE and FOREIGN KEY"), exception.getMessage());
    }

    /**
     * A constraint's name is its identity across branches, so the unnamed forms Postgres would auto-name are not
     * accepted. They reach the parser with nothing identifying them at all, so the message points at the
     * {@code ADD CONSTRAINT} form rather than at a name.
     */
    @Test
    void rejectsUnnamedConstraintForms() {
        for (String ddl : List.of("ALTER TABLE orders ADD PRIMARY KEY (id)",
                "ALTER TABLE orders ADD UNIQUE (email)",
                "ALTER TABLE orders ADD FOREIGN KEY (customer_id) REFERENCES customers (id)")) {
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> parser.parse(ddl), ddl);
            assertTrue(exception.getMessage().contains("ALTER TABLE ADD CONSTRAINT"), exception.getMessage());
        }
    }

    @Test
    void rejectsDropIndexExplainingWhyItCannotBeAttributedToATable() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> parser.parse("DROP INDEX idx_orders_total"));

        assertTrue(exception.getMessage().contains("DROP INDEX is not supported"), exception.getMessage());
        assertTrue(exception.getMessage().contains("names no table"), exception.getMessage());
    }
}
