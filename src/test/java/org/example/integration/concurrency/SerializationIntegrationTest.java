package org.example.integration.concurrency;

import org.example.integration.support.CommandOutput;
import org.example.integration.support.DbGitIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code concurrency-serialization-test.sh}: the core guarantee. Changes to one branch are strictly serialized,
 * however many handler threads are free - {@code dbgit add} validates a statement against the branch's replayed
 * history before running it, so if two adds were ever allowed to overlap they would both validate against the
 * same past and both proceed, leaving the branch's recorded history describing a schema its database does not
 * have.
 */
class SerializationIntegrationTest extends DbGitIntegrationTest {
    private static final int CLIENTS = 5;
    private static final String BRANCH = "serialization";

    /**
     * Serialized, exactly one client can win: the others replay the winner's change first and find the column
     * already there. Unserialized, several would validate against the same empty table and all believe they
     * succeeded.
     */
    @Test
    void exactlyOneOfSeveralConcurrentAddsOfTheSameColumnSucceeds() throws Exception {
        dbgit("checkout", "-b", BRANCH);
        add("CREATE TABLE orders (id INT NOT NULL);");

        List<Callable<CommandOutput>> calls = IntStream.range(0, CLIENTS)
                .<Callable<CommandOutput>>mapToObj(client ->
                        () -> cli.add("ALTER TABLE orders ADD COLUMN contended INT;"))
                .toList();
        List<CommandOutput> results = ConcurrentCalls.run(calls);

        long succeeded = results.stream().filter(CommandOutput::succeeded).count();
        assertEquals(1, succeeded, describe(results));

        String rejections = results.stream()
                .filter(CommandOutput::failed)
                .map(CommandOutput::text)
                .collect(Collectors.joining("\n"));
        assertTrue(rejections.contains("Column already exists"), rejections);

        assertEquals(List.of("id", "contended"), schema.columns(databaseOf(BRANCH), "orders"));
    }

    /** All of these are legitimate, so all must succeed - serialization must order work, not refuse it. */
    @Test
    void allConcurrentAddsOfDistinctColumnsSucceed() throws Exception {
        dbgit("checkout", "-b", BRANCH);
        add("CREATE TABLE orders (id INT NOT NULL);");

        List<Callable<CommandOutput>> calls = IntStream.rangeClosed(1, CLIENTS)
                .<Callable<CommandOutput>>mapToObj(client ->
                        () -> cli.add("ALTER TABLE orders ADD COLUMN col" + client + " INT;"))
                .toList();
        List<CommandOutput> results = ConcurrentCalls.run(calls);

        assertTrue(results.stream().allMatch(CommandOutput::succeeded), describe(results));

        List<String> columns = schema.columns(databaseOf(BRANCH), "orders");
        assertEquals(CLIENTS + 1, columns.size(), columns.toString());
        assertTrue(columns.containsAll(
                IntStream.rangeClosed(1, CLIENTS).mapToObj(client -> "col" + client).toList()), columns.toString());
    }

    private static String describe(List<CommandOutput> results) {
        return results.stream().map(CommandOutput::text).collect(Collectors.joining("\n---\n"));
    }
}
