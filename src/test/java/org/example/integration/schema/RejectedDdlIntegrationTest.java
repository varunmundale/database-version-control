package org.example.integration.schema;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code constraints-rejected-demo.sh}: every way of smuggling a constraint or an index into a {@code CREATE
 * TABLE} is refused, with a message saying what to write instead. Unlike a parser test, each case also checks
 * that nothing was staged and nothing reached the database.
 */
class RejectedDdlIntegrationTest extends DbGitIntegrationTest {

    @BeforeEach
    void onABranchOfItsOwn() {
        initialiseMain();
        dbgit("checkout", "-b", "strict");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a PRIMARY KEY written on a column"
                    + "| CREATE TABLE rejected (id INT PRIMARY KEY);"
                    + "| ADD CONSTRAINT rejected_pkey PRIMARY KEY (id)",
            "a UNIQUE written on a column"
                    + "| CREATE TABLE rejected (email TEXT UNIQUE);"
                    + "| declares UNIQUE",
            "REFERENCES - a foreign key - written on a column"
                    + "| CREATE TABLE rejected (customer_id INT REFERENCES customers(id));"
                    + "| FOREIGN KEY",
            "a PRIMARY KEY as a table-level clause"
                    + "| CREATE TABLE rejected (id INT, PRIMARY KEY (id));"
                    + "| CREATE TABLE defines columns only",
            "a named UNIQUE constraint in the body"
                    + "| CREATE TABLE rejected (id INT, name TEXT, CONSTRAINT uq UNIQUE (name));"
                    + "| CREATE TABLE defines columns only",
            "a CHECK, which the model has no room for"
                    + "| CREATE TABLE rejected (id INT, CHECK (id > 0));"
                    + "| CREATE TABLE defines columns only",
    })
    void aConstraintSmuggledIntoACreateTableIsRefusedAndNothingIsCreated(String description, String ddl, String reason) {
        CommandOutput rejected = cli.add(ddl);

        assertTrue(rejected.failed(), description + " was accepted: " + rejected.text());
        assertTrue(rejected.mentions(reason), description + " was refused, but not because of \"" + reason
                + "\": " + rejected.text());
        assertEquals(java.util.List.of(), schema.tables(databaseOf("strict")),
                "a refused statement must not reach the database");
        assertTrue(dbgit("log").out().contains("Working set: clean."),
                "a refused statement must not be staged");
    }

    /**
     * Two forms are refused for structural reasons rather than for hiding a constraint. {@code CHECK} has nowhere
     * to live in the model, an unnamed constraint has no identity to be known by, and {@code DROP INDEX} names no
     * table while replay is keyed by table.
     */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "a CHECK added separately"
                    + "| ALTER TABLE orders ADD CONSTRAINT positive CHECK (id > 0);"
                    + "| Unsupported constraint type",
            "an unnamed constraint, whose name would be its identity"
                    + "| ALTER TABLE orders ADD PRIMARY KEY (id);"
                    + "| ALTER TABLE ADD CONSTRAINT",
            "DROP INDEX, which names no table to attribute it to"
                    + "| DROP INDEX idx_orders_total;"
                    + "| DROP INDEX is not supported",
            "DROP TABLE IF EXISTS - a statement must mean the same thing on every replay"
                    + "| DROP TABLE IF EXISTS orders;"
                    + "| IF EXISTS",
            "DROP TABLE ... CASCADE - can drop constraints on other tables replay never sees"
                    + "| DROP TABLE orders CASCADE;"
                    + "| CASCADE",
            "DROP TABLE naming several tables at once"
                    + "| DROP TABLE orders, scratch;"
                    + "| Could not parse",
            "CREATE TABLE IF NOT EXISTS - a statement must mean the same thing on every replay"
                    + "| CREATE TABLE IF NOT EXISTS orders (id INT);"
                    + "| IF NOT EXISTS",
            "ALTER TABLE IF EXISTS - a statement must mean the same thing on every replay"
                    + "| ALTER TABLE IF EXISTS orders RENAME TO purchases;"
                    + "| IF EXISTS",
            "RENAME TABLE - MySQL's own spelling, redirected to ALTER TABLE ... RENAME TO"
                    + "| RENAME TABLE orders TO purchases;"
                    + "| ALTER TABLE",
    })
    void aStatementTheModelCannotRepresentIsRefused(String description, String ddl, String reason) {
        add("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10, 2));");
        add("CREATE INDEX idx_orders_total ON orders (total);");

        CommandOutput rejected = cli.add(ddl);

        assertTrue(rejected.failed(), description + " was accepted: " + rejected.text());
        assertTrue(rejected.mentions(reason), description + " was refused, but not because of \"" + reason
                + "\": " + rejected.text());
    }

    /** {@code NOT NULL} and {@code DEFAULT} are properties of the column itself, so they stay allowed. */
    @Test
    void notNullAndDefaultAreColumnPropertiesAndAreAccepted() {
        add("CREATE TABLE orders (id INT NOT NULL, status TEXT DEFAULT 'new');");

        assertEquals(java.util.List.of("id", "status"), schema.columns(databaseOf("strict"), "orders"));
    }

    /** A statement dbgit cannot parse never reaches the database, so a branch is never ahead of its own history. */
    @Test
    void anUnparseableStatementLeavesNeitherAChangesetNorAnythingInTheDatabase() {
        CommandOutput rejected = cli.add("TRUNCATE TABLE orders;");

        assertTrue(rejected.failed(), rejected.text());
        assertEquals(java.util.List.of(), schema.tables(databaseOf("strict")));
        assertTrue(dbgit("log").out().contains("Working set: clean."));
    }
}
