package org.example.integration.concurrency;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitCli;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code concurrency-isolation-test.sh}: two workspaces on one daemon keep separate current branches, and work on
 * different branches proceeds at the same time rather than queueing behind each other. The one integration test
 * here driving two independent workspaces against the same daemon rather than one.
 */
class IsolationIntegrationTest extends DbGitIntegrationTest {

    @Test
    void twoWorkspacesOnOneDaemonKeepSeparateCurrentBranches() throws Exception {
        DbGitCli alice = newWorkspace("alice");
        DbGitCli bob = newWorkspace("bob");

        must(alice.run("checkout", "-b", "alice-branch"));
        must(bob.run("checkout", "-b", "bob-branch"));

        assertEquals("alice-branch", alice.branch());
        assertEquals("bob-branch", bob.branch());
        assertTrue(alice.run("branch").out().contains("* alice-branch"), alice.run("branch").text());
        assertTrue(bob.run("branch").out().contains("* bob-branch"), bob.run("branch").text());

        must(alice.run("checkout", "-b", "alice-third"));

        assertEquals("alice-third", alice.branch(), "alice moved");
        assertEquals("bob-branch", bob.branch(), "bob did not move with her");
    }

    @Test
    void workOnDifferentBranchesProceedsAtTheSameTime() throws Exception {
        DbGitCli alice = newWorkspace("alice");
        DbGitCli bob = newWorkspace("bob");
        must(alice.run("checkout", "-b", "alice-branch"));
        must(bob.run("checkout", "-b", "bob-branch"));

        List<Callable<CommandOutput>> calls = List.of(
                () -> alice.add("CREATE TABLE orders (id INT NOT NULL);"),
                () -> bob.add("CREATE TABLE invoices (id INT NOT NULL);"));
        List<CommandOutput> results = ConcurrentCalls.run(calls);

        assertTrue(results.get(0).succeeded(), "alice's change was accepted: " + results.get(0).text());
        assertTrue(results.get(1).succeeded(), "bob's change was accepted: " + results.get(1).text());

        // Per-branch locking must not leak work between branches.
        assertEquals(List.of("id"), schema.columns(databaseOf("alice-branch"), "orders"));
        assertFalse(schema.tables(databaseOf("alice-branch")).contains("invoices"),
                "alice's branch does not have bob's table");
        assertEquals(List.of("id"), schema.columns(databaseOf("bob-branch"), "invoices"));
        assertFalse(schema.tables(databaseOf("bob-branch")).contains("orders"),
                "bob's branch does not have alice's table");
    }

    private static CommandOutput must(CommandOutput output) {
        assertTrue(output.succeeded(), output.text());
        return output;
    }
}
