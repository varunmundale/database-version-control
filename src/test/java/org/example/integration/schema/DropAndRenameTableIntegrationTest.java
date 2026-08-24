package org.example.integration.schema;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code DROP TABLE} and {@code ALTER TABLE ... RENAME TO}, end to end: staged, applied to a real database, and
 * replayed faithfully by a fork and a reset - the same "the model and the database must agree" property every
 * other statement is held to.
 *
 * <p>{@code table-commands-demo.sh} is the runnable walkthrough this test mirrors.
 */
class DropAndRenameTableIntegrationTest extends DbGitIntegrationTest {

    @Test
    void droppingATableRemovesItFromTheBranchDatabase() {
        initialiseMain();
        dbgit("checkout", "-b", "dropdemo");
        add("CREATE TABLE scratch (id INT NOT NULL);");
        add("CREATE TABLE orders (id INT NOT NULL);");

        CommandOutput result = add("DROP TABLE scratch;");

        assertTrue(result.out().getFirst().endsWith("table 'scratch' dropped."), result.text());
        assertEquals(List.of("orders"), schema.tables(databaseOf("dropdemo")));
    }

    @Test
    void renamingATableRenamesItInTheBranchDatabaseAndKeepsItsColumns() {
        initialiseMain();
        dbgit("checkout", "-b", "renamedemo");
        add("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        add("CREATE INDEX orders_total_idx ON orders (total);");

        CommandOutput result = add("ALTER TABLE orders RENAME TO purchases;");

        assertTrue(result.out().getFirst().endsWith("table 'purchases' now has 2 column(s)."), result.text());
        assertEquals(List.of("purchases"), schema.tables(databaseOf("renamedemo")));
        assertEquals(List.of("id", "total"), schema.columns(databaseOf("renamedemo"), "purchases"));
        // The index followed the rename: it covers the same column, by stable id, under the table's new name.
        assertTrue(schema.indexes(databaseOf("renamedemo"), "purchases").stream()
                .anyMatch(name -> name.contains("orders_total_idx")), "the index should still exist, renamed table and all");
    }

    /**
     * A fork never introspects a database - it rebuilds one by replaying committed DDL from scratch - so this is
     * what proves a drop and a rename replay as faithfully as every other statement dbgit accepts.
     */
    @Test
    void aForkReplaysADropAndARenameSoTheForkedDatabaseMatches() {
        initialiseMain();
        dbgit("checkout", "-b", "history");
        add("CREATE TABLE orders (id INT NOT NULL);");
        add("CREATE TABLE scratch (id INT NOT NULL);");
        add("DROP TABLE scratch;");
        add("ALTER TABLE orders RENAME TO purchases;");
        dbgit("commit");

        dbgit("checkout", "-b", "forked");

        assertEquals(List.of("purchases"), schema.tables(databaseOf("forked")));
        assertEquals(List.of("id"), schema.columns(databaseOf("forked"), "purchases"));
    }

    /**
     * {@code reset} is a rebuild, not an undo: the only way back from a drop is to truncate the history before it
     * and replay from scratch, which is exactly what this checks the real database ends up reflecting.
     */
    @Test
    void aResetBeforeADropBringsTheTableBack() {
        initialiseMain();
        dbgit("checkout", "-b", "resetdemo");
        add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit("commit", "-m", "create", "orders");
        add("DROP TABLE orders;");
        dbgit("commit", "-m", "drop", "orders");

        assertEquals(List.of(), schema.tables(databaseOf("resetdemo")), "dropped, as of the second commit");

        dbgit("reset", "1");

        assertEquals(List.of("orders"), schema.tables(databaseOf("resetdemo")),
                "reset rebuilds the database from the truncated history, so the drop never replays");
    }

    /**
     * dbgit's model does not track foreign keys across tables (an FK's target is resolved by name, not looked up -
     * see SchemaOperationApplier), so it has no way to refuse this itself; the real database does, and that
     * refusal is what Stager's compensating cleanup exists for - the changeset is discarded, not left PENDING.
     */
    @Test
    void droppingATableAForeignKeyReferencesIsRefusedByTheDatabaseAndNeitherStagedNorApplied() {
        initialiseMain();
        dbgit("checkout", "-b", "fkdemo");
        add("CREATE TABLE customers (id INT NOT NULL);");
        add("ALTER TABLE customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);");
        add("CREATE TABLE orders (id INT NOT NULL, customer_id INT);");
        add("ALTER TABLE orders ADD CONSTRAINT orders_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id);");
        dbgit("commit");

        CommandOutput rejected = cli.add("DROP TABLE customers;");

        assertTrue(rejected.failed(), rejected.text());
        assertEquals(List.of("customers", "orders"), schema.tables(databaseOf("fkdemo")));
        assertTrue(dbgit("log").out().contains("Working set: clean."),
                "a statement the database refused must not be left staged");
    }

    @Test
    void renamingATableOntoANameAlreadyInUseIsRefused() {
        initialiseMain();
        dbgit("checkout", "-b", "clashdemo");
        add("CREATE TABLE orders (id INT NOT NULL);");
        add("CREATE TABLE purchases (id INT NOT NULL);");

        CommandOutput rejected = cli.add("ALTER TABLE orders RENAME TO purchases;");

        assertTrue(rejected.failed(), rejected.text());
        assertEquals(List.of("orders", "purchases"), schema.tables(databaseOf("clashdemo")));
    }

    @Test
    void droppingAnUnknownTableIsRefusedAndChangesNothing() {
        initialiseMain();
        dbgit("checkout", "-b", "emptydemo");

        CommandOutput rejected = cli.add("DROP TABLE nosuchtable;");

        assertTrue(rejected.failed(), rejected.text());
        assertEquals(List.of(), schema.tables(databaseOf("emptydemo")));
        assertFalse(dbgit("log").out().stream().anyMatch(line -> line.contains("nosuchtable")));
    }
}
