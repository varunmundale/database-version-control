package org.example.integration.branching;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The walkthrough {@code scratch_scripts.sh} performs, as a test: initialise {@code main}, branch off it, stage
 * DDL, commit, branch again, and diff the two branches.
 *
 * <p>Every assertion about a schema is made against the branch's real database rather than against dbgit's own
 * output, because the thing worth proving here is that the two agree - that a fork really replays its parent's
 * history into a database of its own, and that {@code dbgit add} really applies its statement to that database
 * and to no other.
 */
class BranchWorkflowIntegrationTest extends DbGitIntegrationTest {
    private static final String EMPLOYEES = """
            CREATE TABLE employees (
                id SERIAL,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(150),
                age INT,
                salary DECIMAL(10, 2),
                department VARCHAR(100)
            );""";

    @Test
    void initPointsMainAtARealDatabaseAndSayingItTwiceChangesNothing() {
        CommandOutput first = initialiseMain();
        assertEquals("Branch 'main' now tracks " + TRACKED_DATABASE + "@localhost:5432.", first.out().getFirst());

        CommandOutput again = initialiseMain();
        assertEquals("Branch 'main' already tracks " + TRACKED_DATABASE + "@localhost:5432; connection details refreshed.",
                again.out().getFirst());
        assertEquals(first.out().get(1), again.out().get(1), "the same database must always sign the same");
    }

    /** Before {@code dbgit init}, {@code main} has nowhere to write - and says so rather than using a scratchpad. */
    @Test
    void mainRefusesToStageAnythingUntilItTracksADatabase() {
        CommandOutput rejected = cli.add("CREATE TABLE employees (id INT);");

        assertTrue(rejected.failed());
        assertTrue(rejected.mentions("not tracking a database yet"), rejected.text());
        assertTrue(schema.tables(TRACKED_DATABASE).isEmpty());
    }

    @Test
    void aBranchIsForkedIntoADatabaseOfItsOwnSeededFromItsParentsCommittedHistory() {
        initialiseMain();
        add(EMPLOYEES);
        add("ALTER TABLE employees ADD CONSTRAINT employees_pkey PRIMARY KEY (id);");
        dbgit("commit");

        dbgit("checkout", "-b", "mybranch");

        // The fork is a real database, built by replaying main's history into it - not a view onto main's.
        assertEquals(List.of("employees"), schema.tables(databaseOf("mybranch")));
        assertEquals(List.of("id", "name", "email", "age", "salary", "department"),
                schema.columns(databaseOf("mybranch"), "employees"));
        assertTrue(schema.constraints(databaseOf("mybranch"), "employees").contains("employees_pkey"));
        assertEquals("mybranch", cli.branch());
    }

    @Test
    void stagedDdlLandsInTheCheckedOutBranchesDatabaseAndNoOtherBranchsDatabase() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        add(EMPLOYEES);
        dbgit("commit");
        dbgit("checkout", "-b", "mybranch2");

        add("ALTER TABLE employees ADD COLUMN hire_date DATE;");

        assertTrue(schema.columns(databaseOf("mybranch2"), "employees").contains("hire_date"));
        assertFalse(schema.columns(databaseOf("mybranch"), "employees").contains("hire_date"));
        assertTrue(schema.tables(TRACKED_DATABASE).isEmpty(), "main tracks a database of its own and was never touched");
    }

    @Test
    void branchListsEveryBranchAndMarksTheOneThisWorkspaceIsOn() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        dbgit("checkout", "-b", "mybranch2");

        dbgit("checkout", "mybranch");

        assertEquals(List.of("  main", "* mybranch", "  mybranch2"), dbgit("branch").out());
    }

    /**
     * The whole of {@code scratch_scripts.sh}: two branches diverging from a shared commit, each with its own
     * database, ending in the diff that shows what they no longer have in common.
     */
    @Test
    void twoBranchesDivergeFromASharedCommitAndTheDiffNamesEveryColumnTheyNoLongerShare() {
        initialiseMain();
        dbgit("checkout", "-b", "mybranch");
        add(EMPLOYEES);
        // A CREATE TABLE defines columns and nothing else; constraints are statements of their own.
        add("ALTER TABLE employees ADD CONSTRAINT employees_pkey PRIMARY KEY (id);");
        add("ALTER TABLE employees ADD CONSTRAINT employees_email_key UNIQUE (email);");
        assertEquals(List.of("Created commit #1 for branch 'mybranch' with 3 changeset(s)."), dbgit("commit").out());

        dbgit("checkout", "-b", "mybranch2");
        add("ALTER TABLE employees ADD COLUMN hire_date DATE;");
        add("ALTER TABLE employees ALTER COLUMN department TYPE integer USING department::integer");
        add("ALTER TABLE employees DROP COLUMN salary;");
        add("ALTER TABLE employees ADD COLUMN salary DECIMAL(10, 2);");
        dbgit("commit");

        dbgit("checkout", "mybranch");
        add("ALTER TABLE employees ADD COLUMN end_date DATE;");
        add("ALTER TABLE employees DROP COLUMN age;");
        add("ALTER TABLE employees RENAME COLUMN department TO department1;");
        add("ALTER TABLE employees DROP COLUMN salary;");
        add("ALTER TABLE employees ADD COLUMN salary DECIMAL(10, 2);");
        dbgit("commit");

        // Each branch's database is exactly the schema its own history describes.
        assertEquals(List.of("id", "name", "email", "department1", "end_date", "salary"),
                schema.columns(databaseOf("mybranch"), "employees"));
        assertEquals(List.of("id", "name", "email", "age", "department", "hire_date", "salary"),
                schema.columns(databaseOf("mybranch2"), "employees"));
        assertEquals("integer", schema.columnType(databaseOf("mybranch2"), "employees", "department"));

        List<String> diff = dbgit("diff", "mybranch", "mybranch2").out();

        assertEquals("mybranch vs mybranch2", diff.getFirst());
        assertEquals("- employees", diff.get(1));
        // age lives on only on mybranch2, end_date only on mybranch, and hire_date only on mybranch2.
        assertTrue(diff.contains("  |- age"), diff.toString());
        assertTrue(diff.contains("  |- end_date"), diff.toString());
        assertTrue(diff.contains("  |- hire_date"), diff.toString());
        assertTrue(diff.contains("    |- > ALTER TABLE employees DROP COLUMN age;"), diff.toString());
        assertTrue(diff.contains("    |- < ALTER TABLE employees ADD COLUMN hire_date DATE;"), diff.toString());
        // The rename and the retype are the same column by stable id, so they meet at one node.
        assertTrue(diff.contains("  |- department1 (conflicting)") || diff.contains("  |- department (conflicting)"),
                diff.toString());
    }
}
