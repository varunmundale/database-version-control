package org.example.integration.schema;

import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code constraints-indexes-demo.sh}: constraints and indexes written as statements of their own, so dbgit can
 * see them - and therefore diff them, replay them onto a fork, and notice when two branches clash over one.
 *
 * <p>Each one is checked twice over: that {@code dbgit diff} gives it a node of its own, and that it exists in the
 * branch's real database. Those two agreeing is the whole reason {@code CREATE TABLE} refuses to carry a
 * constraint inline - a constraint dbgit cannot see is one that exists in the database and nowhere else.
 */
class ConstraintsAndIndexesIntegrationTest extends DbGitIntegrationTest {

    private void baseTables() {
        add("""
                CREATE TABLE customers (
                    id INT NOT NULL,
                    region TEXT
                );""");
        add("""
                CREATE TABLE orders (
                    id INT NOT NULL,
                    customer_id INT,
                    email TEXT,
                    total NUMERIC(10, 2)
                );""");
        add("ALTER TABLE customers ADD CONSTRAINT customers_pkey PRIMARY KEY (id);");
        add("ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);");
        add("ALTER TABLE orders ADD CONSTRAINT orders_email_key UNIQUE (email);");
        add("ALTER TABLE orders ADD CONSTRAINT orders_customer_fkey FOREIGN KEY (customer_id) REFERENCES customers (id);");
        add("CREATE INDEX idx_orders_total ON orders (total);");
    }

    @Test
    void everyConstraintAndIndexReachesTheBranchesDatabaseAndSurvivesBeingForked() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        baseTables();
        dbgit("commit");

        List<String> constraints = schema.constraints(databaseOf("mybranch"), "orders");
        assertTrue(constraints.contains("orders_pkey"), constraints.toString());
        assertTrue(constraints.contains("orders_email_key"), constraints.toString());
        assertTrue(constraints.contains("orders_customer_fkey"), constraints.toString());
        assertTrue(schema.indexes(databaseOf("mybranch"), "orders").contains("idx_orders_total"));

        // A fork replays that history into a database of its own, constraints and indexes included.
        dbgit("checkout", "-b", "reporting");

        assertEquals(constraints, schema.constraints(databaseOf("reporting"), "orders"));
        assertTrue(schema.indexes(databaseOf("reporting"), "orders").contains("idx_orders_total"));
    }

    @Test
    void constraintsAndIndexesGetNodesOfTheirOwnInADiffAlongsideColumns() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        baseTables();
        dbgit("commit");

        dbgit("checkout", "-b", "reporting");
        add("CREATE INDEX idx_orders_customer ON orders (customer_id);");
        dbgit("commit");

        dbgit("checkout", "mybranch");
        dbgit("checkout", "-b", "pricing");
        add("ALTER TABLE orders ADD CONSTRAINT orders_region_key UNIQUE (total);");
        dbgit("commit");

        List<String> diff = dbgit("diff", "reporting", "pricing").out();

        assertEquals("reporting vs pricing", diff.getFirst());
        assertTrue(diff.contains("  |- orders_region_key (UNIQUE)"), diff.toString());
        assertTrue(diff.contains("  |- idx_orders_customer (INDEX)"), diff.toString());
        assertTrue(diff.contains("    |- > CREATE INDEX idx_orders_customer ON orders (customer_id);"), diff.toString());
        assertTrue(diff.contains("    |- < ALTER TABLE orders ADD CONSTRAINT orders_region_key UNIQUE (total);"),
                diff.toString());
    }

    /** A constraint can be dropped by name, because {@code ALTER TABLE} says which table it belongs to. */
    @Test
    void droppingAConstraintRemovesItFromTheDatabaseAndFromTheDiff() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        baseTables();
        dbgit("commit");
        dbgit("checkout", "-b", "pricing");
        add("ALTER TABLE orders ADD CONSTRAINT orders_region_key UNIQUE (total);");
        dbgit("commit");
        assertTrue(schema.constraints(databaseOf("pricing"), "orders").contains("orders_region_key"));

        add("ALTER TABLE orders DROP CONSTRAINT orders_region_key;");
        dbgit("commit");

        assertFalse(schema.constraints(databaseOf("pricing"), "orders").contains("orders_region_key"));
        assertFalse(dbgit("diff", "mybranch", "pricing").out().stream()
                .anyMatch(line -> line.contains("orders_region_key")));
    }

    /**
     * An index is held by the stable ids of the columns it covers, not by their names, so renaming a column it
     * covers leaves the index alone rather than reading as a drop and an add.
     */
    @Test
    void anIndexSurvivesARenameOfTheColumnItCovers() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        baseTables();
        dbgit("commit");
        dbgit("checkout", "-b", "pricing");

        add("ALTER TABLE orders RENAME COLUMN total TO amount;");
        dbgit("commit");

        assertTrue(schema.columns(databaseOf("pricing"), "orders").contains("amount"));
        assertTrue(schema.indexes(databaseOf("pricing"), "orders").contains("idx_orders_total"),
                "the index moved with the column it covers");

        // A rename reads as one column that differs, not a drop and an add, and is not conflicting: only 'pricing'
        // touched it, so it's a change 'mybranch' hasn't received yet, not a disagreement.
        List<String> diff = dbgit("diff", "mybranch", "pricing").out();
        assertTrue(diff.contains("  |- total"), diff.toString());
        assertFalse(diff.stream().anyMatch(line -> line.contains("(conflicting)")), diff.toString());
        assertTrue(diff.contains("    |- < ALTER TABLE orders RENAME COLUMN total TO amount;"), diff.toString());
        assertFalse(diff.stream().anyMatch(line -> line.contains("idx_orders_total")),
                "the index itself did not change: " + diff);
    }
}
