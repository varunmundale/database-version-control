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

    /**
     * Once {@code mb1} has been merged into {@code mb}, {@code mb}'s flattened history holds mb1's contributed
     * changeset (the {@code sku} column) *after* mb's own unique commit ({@code owner}) - the second parent's
     * content sorts after the first parent's own. Merging back the other way, {@code mb} into {@code mb1}, used to
     * compare the two branches' flattened histories position by position, so it never got far enough past mb's own
     * commit to see that the {@code sku} commit was shared, and replayed it a second time - failing with "column
     * already exists" even though the only genuinely new thing to bring in is {@code owner}.
     */
    @Test
    void mergingBackTheOtherWayAfterAPriorMergeOnlyBringsInTheGenuinelyNewColumn() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE products (id SERIAL, name VARCHAR(100) NOT NULL);");
        dbgit("commit");

        dbgit("checkout", "-b", "mb");
        add("ALTER TABLE products ADD COLUMN owner VARCHAR(50);");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "mb1");
        add("ALTER TABLE products ADD COLUMN sku VARCHAR(50);");
        dbgit("commit");

        dbgit("checkout", "mb");
        CommandOutput merged = dbgit("merge", "mb1");
        assertTrue(merged.out().getFirst().startsWith("Merged 'mb1' into 'mb' as commit #"), merged.text());
        assertEquals(List.of("id", "name", "owner", "sku"), schema.columns(databaseOf("mb"), "products"));

        dbgit("checkout", "mb1");
        CommandOutput reverseMerged = dbgit("merge", "mb");
        assertEquals("Merged 'mb' into 'mb1' as commit #5, applying 1 changeset(s).", reverseMerged.out().getFirst());
        assertEquals(List.of("id", "name", "sku", "owner"), schema.columns(databaseOf("mb1"), "products"));
    }

    /**
     * Resolving a conflict the other way round: instead of adding a compensating statement, the branch resets away
     * its own conflicting commit. Once it has, only the other branch has touched the column, so there is nothing
     * left to disagree about and the merge simply brings that change in.
     *
     * <p>This used to fail. A conflict was decided by comparing the two branches' schemas alone, so a column that
     * read {@code VARCHAR(20)} on one side and {@code VARCHAR(10)} on the other was called a conflict no matter
     * which of them had changed it - and the reset, which had put the column back exactly as the shared history
     * left it, changed nothing about that answer.
     */
    @Test
    void resettingAwayTheConflictingCommitLetsTheMergeThroughAndBringsInTheOtherBranchsChange() {
        initialiseMain();
        dbgit("checkout", "-b", "current");
        add("""
                CREATE TABLE employees (
                    id SERIAL,
                    name VARCHAR(100) NOT NULL,
                    department VARCHAR(100)
                );""");
        dbgit("commit");

        dbgit("checkout", "-b", "other");
        add("ALTER TABLE employees ADD COLUMN other_column DATE;");
        String otherOwnCommit = dbgit("commit").out().getFirst().replaceAll("^Created commit #(\\d+).*$", "$1");

        // Both branches retype the same column, incompatibly.
        dbgit("checkout", "current");
        add("ALTER TABLE employees ALTER COLUMN department TYPE VARCHAR(10);");
        dbgit("commit");

        dbgit("checkout", "other");
        add("ALTER TABLE employees ALTER COLUMN department TYPE VARCHAR(20);");
        dbgit("commit");

        CommandOutput rejected = cli.run("merge", "current");
        assertTrue(rejected.failed(), rejected.text());
        assertTrue(rejected.mentions("conflicting changes to table 'employees', column 'department'"), rejected.text());

        // Reset 'other' back to its own last commit, dropping its retype. Now only 'current' has touched the column.
        dbgit("reset", otherOwnCommit);
        List<String> diff = dbgit("diff", "other", "current").out();
        assertTrue(diff.contains("  |- department"), diff.toString());
        assertFalse(diff.stream().anyMatch(line -> line.contains("(conflicting)")), diff.toString());

        CommandOutput merged = dbgit("merge", "current");
        assertTrue(merged.out().getFirst().startsWith("Merged 'current' into 'other' as commit #"), merged.text());
        assertEquals("10", schema.columnLength(databaseOf("other"), "employees", "department"));
    }

    /**
     * The other way of resolving the same conflict as the reset above: 'other' compensates, retyping the column
     * back to what the shared history declared - and writes the type in the case it feels like, since a type is
     * keywords rather than data. Once it has, only 'current' has moved the column, so the merge goes through and
     * brings 'current's type in.
     *
     * <p>This used to be refused. The compensating {@code varchar(100)} was compared as a string against the
     * {@code VARCHAR(100)} the {@code CREATE TABLE} recorded, so 'other' still counted as having changed the
     * column and the conflict outlived the statement written to resolve it.
     */
    @Test
    void compensatingBackToTheSharedTypeResolvesTheConflictWhateverCaseTheTypeIsWrittenIn() {
        initialiseMain();
        dbgit("checkout", "-b", "current");
        add("""
                CREATE TABLE employees (
                    id SERIAL,
                    name VARCHAR(100) NOT NULL,
                    department VARCHAR(100)
                );""");
        dbgit("commit");

        dbgit("checkout", "-b", "other");
        add("ALTER TABLE employees ADD COLUMN other_column DATE;");
        dbgit("commit");

        // As the demo does it: 'other' goes into 'current' first, so the two share that commit through a merge.
        dbgit("checkout", "current");
        dbgit("merge", "other");
        add("ALTER TABLE employees ALTER COLUMN department TYPE VARCHAR(10);");
        dbgit("commit");

        dbgit("checkout", "other");
        add("ALTER TABLE employees ALTER COLUMN department TYPE VARCHAR(20);");
        dbgit("commit");
        assertTrue(cli.run("merge", "current").failed());

        // Undo it by hand, in lower case: back to the type the shared CREATE TABLE declared.
        add("ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);");
        dbgit("commit");

        List<String> diff = dbgit("diff", "other", "current").out();
        assertFalse(diff.stream().anyMatch(line -> line.contains("(conflicting)")), diff.toString());

        CommandOutput merged = dbgit("merge", "current");
        assertTrue(merged.out().getFirst().startsWith("Merged 'current' into 'other' as commit #"), merged.text());
        assertEquals("10", schema.columnLength(databaseOf("other"), "employees", "department"));

        // And with the merge done the two schemas agree: the histories still differ (other's retype and its undo
        // are its own), so the diff keeps its header, but there is no table, column or index node left under it.
        List<String> after = dbgit("diff", "other", "current").out();
        assertEquals(List.of("other vs current"), after);
    }

    @Test
    void aBranchCannotBeMergedIntoItself() {
        initialiseMain();
        dbgit("checkout", "-b", "solo");

        CommandOutput rejected = cli.run("merge", "solo");

        assertTrue(rejected.failed());
        assertTrue(rejected.mentions("Cannot merge branch 'solo' into itself."), rejected.text());
    }

    /**
     * A one-sided table rename is just a change the target hasn't received yet, exactly like a one-sided column
     * change - it merges in cleanly, and afterwards the two branches' schemas agree entirely (their histories now
     * do too, since the merge commit brought every one of the other branch's commits into the target's ancestry).
     */
    @Test
    void mergingABranchThatRenamedATableBringsTheRenameIn() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE orders (id SERIAL, total NUMERIC(10,2));");
        dbgit("commit");

        dbgit("checkout", "-b", "renamed");
        add("ALTER TABLE orders RENAME TO purchases;");
        dbgit("commit");

        dbgit("checkout", "base");
        CommandOutput merged = dbgit("merge", "renamed");

        assertEquals("Merged 'renamed' into 'base' as commit #3, applying 1 changeset(s).", merged.out().getFirst());
        assertEquals(List.of("purchases"), schema.tables(databaseOf("base")));
        assertEquals(List.of("id", "total"), schema.columns(databaseOf("base"), "purchases"));
        assertEquals(List.of("No differences between 'base' and 'renamed'."), dbgit("diff", "base", "renamed").out());
    }

    /** Both branches renamed the same table, differently - a table-level conflict, reported the same way a column one is. */
    @Test
    void bothBranchesRenamingTheSameTableIsRefusedAsATableLevelConflict() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE orders (id SERIAL);");
        dbgit("commit");

        dbgit("checkout", "-b", "toPurchases");
        add("ALTER TABLE orders RENAME TO purchases;");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "toSales");
        add("ALTER TABLE orders RENAME TO sales;");
        dbgit("commit");

        assertTrue(dbgit("diff", "toPurchases", "toSales").out().contains("- purchases (conflicting)"));

        dbgit("checkout", "toPurchases");
        CommandOutput rejected = cli.run("merge", "toSales");

        assertTrue(rejected.failed(), rejected.text());
        assertTrue(rejected.mentions("conflicting changes to table 'purchases'"), rejected.text());
        assertEquals(List.of("purchases"), schema.tables(databaseOf("toPurchases")),
                "the rejected merge must not have touched the target's database");
        assertEquals(List.of("  base", "  main", "* toPurchases", "  toSales"), dbgit("branch").out());
    }

    /**
     * A merge replays the other branch's raw DDL text. Its statement names 'orders', but the target renamed that
     * table to 'purchases' - so the replay fails against the staging branch's real database before the target's
     * own database is ever touched. Not a conflict dbgit's model can see: id-wise nothing here disagrees, since the
     * other branch never touched the table's identity, only added a column to what it still calls 'orders'.
     */
    @Test
    void mergingAStatementNamingATableTheOtherBranchRenamedFailsInStagingAndLeavesTheTargetIntact() {
        initialiseMain();
        dbgit("checkout", "-b", "base");
        add("CREATE TABLE orders (id SERIAL);");
        dbgit("commit");

        dbgit("checkout", "-b", "renamer");
        add("ALTER TABLE orders RENAME TO purchases;");
        dbgit("commit");

        dbgit("checkout", "base");
        dbgit("checkout", "-b", "adder");
        add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        dbgit("commit");

        // Not flagged as a conflict: the 'total' column is a one-sided addition, and adder never touched the
        // table's own identity, so the diff has nothing to refuse the merge over.
        assertFalse(dbgit("diff", "renamer", "adder").out().stream().anyMatch(line -> line.contains("(conflicting)")));

        dbgit("checkout", "renamer");
        CommandOutput rejected = cli.run("merge", "adder");

        assertTrue(rejected.failed(), rejected.text());
        assertTrue(rejected.mentions("Could not replay changeset"), rejected.text());
        assertEquals(List.of("purchases"), schema.tables(databaseOf("renamer")),
                "the failure is in the staging replay, before the target's own database is ever touched");
        assertEquals(List.of("id"), schema.columns(databaseOf("renamer"), "purchases"));
        assertTrue(dbgit("branch").out().stream().noneMatch(line -> line.contains("merge/")),
                "the staging branch is dropped in a finally, whether the merge succeeded or not");
    }
}
