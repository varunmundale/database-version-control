package org.example.integration.history;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code log-reset-demo.sh}: a branch with two commits and an uncommitted working set, its log, and what a reset
 * back to the first commit does to both the history and the database.
 *
 * <p>A reset is deliberately a rebuild rather than an undo - dbgit has no notion of an inverse DDL statement - so
 * what the database looks like afterwards is the assertion that matters most here.
 */
class LogAndResetIntegrationTest extends DbGitIntegrationTest {
    private static final String BRANCH = "log-reset";

    /** Builds the branch the demo script does: two commits, then one changeset staged and left uncommitted. */
    private void twoCommitsAndAWorkingSet() {
        initialiseMain();
        dbgit("checkout", "-b", BRANCH);
        add("""
                CREATE TABLE orders (
                    id INT NOT NULL,
                    placed_on DATE
                );""");
        dbgit("commit", "-m", "create", "the", "orders", "table");
        add("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        dbgit("commit", "-m", "add", "a", "total", "column");
        add("ALTER TABLE orders ADD COLUMN note TEXT;");
    }

    @Test
    void theLogShowsTheWorkingSetFirstThenTheCommitsNewestFirst() {
        twoCommitsAndAWorkingSet();

        List<String> log = dbgit("log").out();

        assertEquals("Branch '" + BRANCH + "'", log.getFirst());
        assertEquals("Working set (1 uncommitted changeset(s)):", log.get(2));
        assertEquals("  #3 [APPLIED] ALTER TABLE orders ADD COLUMN note TEXT;", log.get(3));
        // Newest commit first, each with who wrote it, why, and the changesets it folded in.
        assertEquals("commit #2", log.get(5));
        // The author comes from initialiseMain()'s 'dbgit init --author integration-test'.
        assertEquals("Author:     integration-test", log.get(6));
        assertEquals("Message:    add a total column", log.get(8));
        assertEquals("  #2 ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);", log.get(10));
        assertEquals("commit #1", log.get(12));
        assertEquals("Message:    create the orders table", log.get(15));
        assertTrue(log.contains("Changesets:"));
    }

    @Test
    void aBranchWithNothingStagedReportsACleanWorkingSet() {
        initialiseMain();
        dbgit("checkout", "-b", BRANCH);

        List<String> log = dbgit("log").out();

        assertTrue(log.contains("Working set: clean."), log.toString());
        assertTrue(log.contains("No commits on branch '" + BRANCH + "' yet."), log.toString());
    }

    /**
     * The reset rebuilds the database from the truncated history, so the column the second commit added is gone
     * from the database as well as from the history - and so is the changeset that was never committed at all.
     */
    @Test
    void resettingToTheFirstCommitDropsTheWorkingSetAndRebuildsTheDatabase() {
        twoCommitsAndAWorkingSet();
        assertEquals(List.of("id", "placed_on", "total", "note"), schema.columns(databaseOf(BRANCH), "orders"));

        CommandOutput reset = dbgit("reset", "1");

        assertEquals(List.of(
                "Branch '" + BRANCH + "' reset to commit #1.",
                "Dropped 1 working changeset(s); replayed 1 committed changeset(s) into a rebuilt database.",
                "Schema now has 1 table(s)."), reset.out());
        assertEquals(List.of("id", "placed_on"), schema.columns(databaseOf(BRANCH), "orders"));

        List<String> log = dbgit("log").out();
        assertTrue(log.contains("Working set: clean."), log.toString());
        assertTrue(log.contains("commit #1"), log.toString());
        assertFalse(log.contains("commit #2"), log.toString());
    }

    /** The printed form is accepted as readily as the bare number. */
    @Test
    void aCommitIdIsAcceptedWithOrWithoutItsHash() {
        twoCommitsAndAWorkingSet();

        assertTrue(dbgit("reset", "#1").out().getFirst().endsWith("reset to commit #1."));
    }

    @Test
    void resettingToACommitThatIsNotInThisBranchsHistoryIsRefused() {
        twoCommitsAndAWorkingSet();
        dbgit("commit");
        dbgit("checkout", "-b", "sibling");
        add("ALTER TABLE orders ADD COLUMN sibling_only TEXT;");
        String siblingsCommit = dbgit("commit").out().getFirst().replaceAll("^Created commit #(\\d+).*$", "$1");

        // A real commit, but not one this branch can be taken back to - it belongs to the other branch alone.
        dbgit("checkout", BRANCH);
        CommandOutput refused = cli.run("reset", siblingsCommit);

        assertTrue(refused.failed(), refused.text());
        assertTrue(refused.mentions("is not in branch '" + BRANCH + "' history"), refused.text());
    }

    /**
     * {@code main} tracks a real, pre-existing database rather than a scratchpad fork, and a reset drops and
     * rebuilds the database it acts on - which for {@code main} would destroy data dbgit never created.
     */
    @Test
    void resetIsRefusedOnMain() {
        twoCommitsAndAWorkingSet();
        dbgit("checkout", "main");

        CommandOutput refused = cli.run("reset", "1");

        assertTrue(refused.failed(), refused.text());
        assertTrue(refused.mentions("Cannot reset branch 'main'"), refused.text());
    }
}
