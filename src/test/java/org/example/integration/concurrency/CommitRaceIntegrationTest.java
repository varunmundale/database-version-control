package org.example.integration.concurrency;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code concurrency-commit-race-test.sh}: concurrent commits on one branch do not lose each other.
 *
 * <p>The failure this guards against is the worst one in the system, and it is silent. {@code commit} reads the
 * branch's HEAD, then writes a new commit parented on it. Two commits reading the same HEAD both succeed, the
 * second overwrites it, and the first becomes reachable from nothing - while its changesets are already marked
 * COMMIT, so neither the working set nor a reset can ever find them again.
 */
class CommitRaceIntegrationTest extends DbGitIntegrationTest {
    private static final int COMMITTERS = 4;
    private static final String BRANCH = "commit-race";

    /** A committed changeset line in {@code dbgit log}, e.g. {@code "  #2 ALTER TABLE ..."} - not a working-set
     *  entry, which carries a {@code [STATUS]} marker straight after its id. */
    private static final Pattern COMMITTED_CHANGESET = Pattern.compile("^ {2}#(\\d+) (?!\\[).*");

    /**
     * Each client stages its own column and commits. Whose changeset ends up in whose commit is not determined -
     * what must hold is that every one of them is still reachable by walking the branch's commits afterwards.
     */
    @Test
    void concurrentCommitsOnOneBranchDoNotLoseEachOther() throws Exception {
        dbgit("checkout", "-b", BRANCH);
        add("CREATE TABLE orders (id INT NOT NULL);");
        dbgit("commit", "-m", "base", "table");

        List<Callable<CommandOutput>> calls = IntStream.rangeClosed(1, COMMITTERS)
                .<Callable<CommandOutput>>mapToObj(committer -> () -> {
                    CommandOutput added = cli.add("ALTER TABLE orders ADD COLUMN col" + committer + " INT;");
                    assertTrue(added.succeeded(), added.text());
                    return cli.run("commit", "-m", "add", "col" + committer);
                })
                .toList();
        ConcurrentCalls.run(calls);

        List<String> log = dbgit("log").out();
        List<Integer> committed = committedChangesetIds(log);

        assertEquals(COMMITTERS + 1, committed.size(), "every changeset staged on the branch is still reachable: " + log);
        assertEquals(committed.size(), committed.stream().distinct().count(), "and none of them is reachable twice: " + log);
        assertTrue(log.contains("Working set: clean."), "nothing is left uncommitted: " + log);

        for (int committer = 1; committer <= COMMITTERS; committer++) {
            String needle = "ADD COLUMN col" + committer;
            assertTrue(log.stream().anyMatch(line -> line.contains(needle)), needle + " survived the race: " + log);
        }
    }

    private static List<Integer> committedChangesetIds(List<String> log) {
        return log.stream()
                .map(COMMITTED_CHANGESET::matcher)
                .filter(Matcher::matches)
                .map(matcher -> Integer.parseInt(matcher.group(1)))
                .toList();
    }
}
