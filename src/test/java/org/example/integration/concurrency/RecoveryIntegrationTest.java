package org.example.integration.concurrency;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code concurrency-recovery-test.sh}: an operation that fails part-way leaves nothing broken behind.
 *
 * <p>The metadata write and the real database change cannot be one transaction - dbgit records a changeset, then
 * runs DDL, then marks it applied; a merge claims a staging branch, then builds its database. Anything that fails
 * in between has to be compensated for, or the leftovers are worse than the failure: a changeset stuck at PENDING
 * can never be committed yet still counts as working set, and a staging branch left behind could collide with the
 * next merge of the same pair.
 */
class RecoveryIntegrationTest extends DbGitIntegrationTest {
    private static final String BRANCH = "recovery";
    private static final Pattern WORKING_SET_SIZE = Pattern.compile("^Working set \\((\\d+) uncommitted.*");

    /**
     * The statement parses and the in-memory column model accepts it, so a changeset row is written - and only
     * then does the database refuse the unknown type. That row describes something which never happened and must
     * not survive.
     */
    @Test
    void ddlDbgitAcceptsButTheDatabaseRejectsLeavesNoChangesetBehind() {
        dbgit("checkout", "-b", BRANCH);
        add("CREATE TABLE orders (id INT NOT NULL);");
        int before = workingSetSize();

        CommandOutput rejected = cli.add("ALTER TABLE orders ADD COLUMN broken NOTATYPE;");
        assertTrue(rejected.failed(), "the database should have rejected an unknown column type");

        assertEquals(before, workingSetSize(), "no changeset is left behind for DDL that never ran");
        List<String> log = dbgit("log").out();
        assertFalse(log.stream().anyMatch(line -> line.contains("NOTATYPE")),
                "the failed statement is nowhere in the branch's history: " + log);

        // The branch is still usable afterwards, rather than wedged behind a stuck changeset.
        assertTrue(add("ALTER TABLE orders ADD COLUMN recovered INT;").succeeded());
    }

    /**
     * A merge forks a scratch branch to prove the replay before touching the target's real database. That scratch
     * branch is an implementation detail: it must not be left lying around afterwards.
     */
    @Test
    void mergingCleansUpItsStagingBranchAfterwards() {
        dbgit("checkout", "-b", BRANCH);
        add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit("commit");
        dbgit("checkout", "-b", "merge-source");
        add("ALTER TABLE orders ADD COLUMN merged_in INT;");
        dbgit("commit");
        dbgit("checkout", BRANCH);

        CommandOutput merged = dbgit("merge", "merge-source");

        assertTrue(merged.text().contains("Validated via staging branch"), merged.text());
        assertFalse(dbgit("branch").out().stream().anyMatch(line -> line.contains("merge/")),
                "no staging branch is left behind: " + dbgit("branch").out());
    }

    private int workingSetSize() {
        List<String> log = dbgit("log").out();
        if (log.contains("Working set: clean.")) {
            return 0;
        }
        return log.stream()
                .map(WORKING_SET_SIZE::matcher)
                .filter(Matcher::matches)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .findFirst()
                .orElse(0);
    }
}
