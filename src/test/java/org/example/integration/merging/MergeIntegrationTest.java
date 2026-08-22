package org.example.integration.merging;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two merge walkthroughs, {@code merge-demo-non-conflicting.sh} and {@code merge-demo-conflicting.sh}: two
 * branches forked from the same commit, one pair touching different columns and one pair touching the same column
 * incompatibly.
 *
 * <p>A merge is the operation with the most to go wrong outside the metadata store - it forks a staging branch,
 * replays the other side's changesets into that database, replays them again into the target's own database, and
 * only then records a two-parent commit - so these tests check the databases as much as the output: that the
 * target really gained the other branch's columns, and that a rejected merge left no database, no branch and no
 * commit behind.
 */
class MergeIntegrationTest extends DbGitIntegrationTest {

    @Test
    void mergingABranchThatTouchedDifferentColumnsBringsThemIntoTheTargetsOwnDatabase() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("""
                CREATE TABLE products (
                    id SERIAL,
                    name VARCHAR(100) NOT NULL,
                    price DECIMAL(10, 2)
                );""");
        add("ALTER TABLE products ADD CONSTRAINT products_pkey PRIMARY KEY (id);");
        dbgit("commit");

        dbgit("checkout", "-b", "sku");
        add("ALTER TABLE products ADD COLUMN sku VARCHAR(50);");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "notes");
        add("ALTER TABLE products ADD COLUMN notes TEXT;");
        dbgit("commit");

        // Before the merge the two branches differ by one column each, and neither is conflicting.
        List<String> before = dbgit("diff", "sku", "notes").out();
        assertTrue(before.contains("  |- sku"), before.toString());
        assertTrue(before.contains("  |- notes"), before.toString());
        assertFalse(before.stream().anyMatch(line -> line.contains("(conflicting)")), before.toString());

        dbgit("checkout", "sku");
        CommandOutput merged = dbgit("merge", "notes");

        assertEquals("Merged 'notes' into 'sku' as commit #4, applying 1 changeset(s).", merged.out().getFirst());
        assertTrue(merged.out().get(1).startsWith("Validated via staging branch 'merge/sku-notes-"), merged.text());

        // The point of the whole operation: sku's real database now carries both columns.
        assertEquals(List.of("id", "name", "price", "sku", "notes"),
                schema.columns(databaseOf("sku"), "products"));

        // The staging branch existed only to prove the replay, and is gone either way.
        assertEquals(List.of("  base", "  main", "  notes", "* sku"), dbgit("branch").out());
    }

    /**
     * A merge only pulls the other branch's changes in; it does not push the current branch's back out. So after
     * merging, the column {@code sku} added on this side is still a one-sided difference.
     */
    @Test
    void aMergeIsOneDirectionalSoTheTargetsOwnChangesRemainADifference() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE products (id SERIAL, name VARCHAR(100) NOT NULL);");
        dbgit("commit");
        dbgit("checkout", "-b", "sku");
        add("ALTER TABLE products ADD COLUMN sku VARCHAR(50);");
        dbgit("commit");
        dbgit("checkout", "base");
        dbgit("checkout", "-b", "notes");
        add("ALTER TABLE products ADD COLUMN notes TEXT;");
        dbgit("commit");
        dbgit("checkout", "sku");
        dbgit("merge", "notes");

        List<String> after = dbgit("diff", "sku", "notes").out();

        assertTrue(after.contains("  |- sku"), after.toString());
        assertFalse(after.contains("  |- notes"), "notes is now on both sides: " + after);
    }

    /** A branch whose history the current one already contains has nothing to contribute. */
    @Test
    void mergingAnAncestorBringsInNothingAndSaysSo() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE products (id SERIAL);");
        dbgit("commit");
        dbgit("checkout", "-b", "sku");
        add("ALTER TABLE products ADD COLUMN sku VARCHAR(50);");
        dbgit("commit");

        assertEquals(List.of("Already up to date."), dbgit("merge", "base").out());
    }

    @Test
    void aMergeOfTwoBranchesThatRetypedTheSameColumnIsRefusedAndChangesNothing() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("""
                CREATE TABLE invoices (
                    id SERIAL,
                    amount DECIMAL(10, 2)
                );""");
        add("ALTER TABLE invoices ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);");
        dbgit("commit");

        dbgit("checkout", "-b", "num");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE NUMERIC(14, 4);");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "big");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;");
        dbgit("commit");

        // The same column by stable id, retyped differently on each side - a genuine conflict, and the diff says so.
        assertTrue(dbgit("diff", "num", "big").out().contains("  |- amount (conflicting)"));

        dbgit("checkout", "num");
        CommandOutput rejected = cli.run("merge", "big");

        assertTrue(rejected.failed(), rejected.text());
        assertTrue(rejected.mentions("conflicting changes to table 'invoices', column 'amount'"), rejected.text());

        // Nothing got as far as being created: no staging branch, no merge commit, no change to either database.
        assertEquals(List.of("  base", "  big", "  main", "* num"), dbgit("branch").out());
        assertEquals("numeric", schema.columnType(databaseOf("num"), "invoices", "amount"));
        assertEquals("bigint", schema.columnType(databaseOf("big"), "invoices", "amount"));
        assertFalse(dbgit("log").out().stream().anyMatch(line -> line.startsWith("commit #4")),
                "num's history must be untouched by the rejected merge");
    }

    /**
     * A rejected merge is not a dead end: {@code dbgit} has no notion of an automatic conflict resolution, so the
     * only way past a genuine conflict is to add a new, compensating DDL statement - the same way any other change
     * is staged, via {@code dbgit add} + {@code dbgit commit} - that makes the two branches agree again on the
     * conflicting column, then retry the merge. Here the compensation lands on the *current* branch ('num'),
     * retyping 'amount' to match what 'big' already settled on.
     */
    @Test
    void aMergeConflictCanBeResolvedByAddingACompensatingStatementToTheCurrentBranch() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("""
                CREATE TABLE invoices (
                    id SERIAL,
                    amount DECIMAL(10, 2)
                );""");
        add("ALTER TABLE invoices ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);");
        dbgit("commit");

        dbgit("checkout", "-b", "num");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE NUMERIC(14, 4);");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "big");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;");
        dbgit("commit");

        dbgit("checkout", "num");
        CommandOutput rejected = cli.run("merge", "big");
        assertTrue(rejected.failed(), rejected.text());
        assertTrue(rejected.mentions("conflicting changes to table 'invoices', column 'amount'"), rejected.text());
        assertTrue(rejected.mentions(
                "Resolve by adding a compensating DDL statement ('dbgit add' + 'dbgit commit') to 'num' or 'big'"),
                rejected.text());

        // Resolve on 'num' (the current branch): retype 'amount' to the same type 'big' already carries, so the
        // two branches' replayed schemas agree again for that column.
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;");
        dbgit("commit");
        assertFalse(dbgit("diff", "num", "big").out().stream().anyMatch(line -> line.contains("(conflicting)")),
                "amount should no longer be conflicting once both branches agree on its type");

        CommandOutput merged = dbgit("merge", "big");
        assertTrue(merged.out().getFirst().startsWith("Merged 'big' into 'num' as commit #"), merged.text());
        assertEquals("bigint", schema.columnType(databaseOf("num"), "invoices", "amount"));
    }

    /** The same resolution, but the compensating statement lands on the *other* branch ('big') instead. */
    @Test
    void aMergeConflictCanBeResolvedByAddingACompensatingStatementToTheOtherBranch() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("""
                CREATE TABLE invoices (
                    id SERIAL,
                    amount DECIMAL(10, 2)
                );""");
        add("ALTER TABLE invoices ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);");
        dbgit("commit");

        dbgit("checkout", "-b", "num");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE NUMERIC(14, 4);");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "big");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;");
        dbgit("commit");

        dbgit("checkout", "num");
        CommandOutput rejected = cli.run("merge", "big");
        assertTrue(rejected.failed(), rejected.text());

        // Resolve on 'big' (the other branch) instead: retype 'amount' to match 'num's own type.
        dbgit("checkout", "big");
        add("ALTER TABLE invoices ALTER COLUMN amount TYPE NUMERIC(14, 4);");
        dbgit("commit");

        dbgit("checkout", "num");
        CommandOutput merged = dbgit("merge", "big");
        assertTrue(merged.out().getFirst().startsWith("Merged 'big' into 'num' as commit #"), merged.text());
        assertEquals("numeric", schema.columnType(databaseOf("num"), "invoices", "amount"));
    }

    @Test
    void aBranchCannotBeMergedIntoItself() {
        initialiseMain();
        dbgit("checkout", "-b", "solo");

        CommandOutput rejected = cli.run("merge", "solo");

        assertTrue(rejected.failed());
        assertTrue(rejected.mentions("Cannot merge branch 'solo' into itself."), rejected.text());
    }
}
