package org.example.unit.core.differ;


import org.example.core.differ.Differ;
import org.example.core.differ.HistoryDiffFormatter;
import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.ChangesetStatus;
import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitEntry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives {@link Differ} - the entry point both {@code dbgit diff} and a merge go through - and asserts on what it
 * found as {@link HistoryDiffFormatter} renders it, since the rendered tree is the one place every part of a
 * comparison (which objects differ, which side each statement came from, what conflicts) is visible at once.
 */
class DifferTest {
    private final Differ differ = new Differ();
    private final HistoryDiffFormatter formatter = new HistoryDiffFormatter();
    private long changesetIdSequence = 1;
    private long commitIdSequence = 1;

    @Test
    void identicalHistoriesProduceNoLines() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL);");

        List<String> lines = diff("left", "right", List.of(create), List.of(create));

        assertEquals(List.of(), lines);
    }

    @Test
    void aColumnChangedOnlyOnOneSideIsNotLabeledAsAConflict() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL);");
        CommitEntry addColumn = commit("ALTER TABLE orders ADD COLUMN total INT;");

        List<String> lines = diff("main", "feature/orders", List.of(create, addColumn), List.of(create));

        assertEquals(List.of(
                "main vs feature/orders",
                "- orders",
                "  |- total",
                "    |- > ALTER TABLE orders ADD COLUMN total INT;"
        ), lines);
    }

    @Test
    void bothSidesAddingDifferentColumnsToTheSameTableIsNotAConflictAccordingToDatabaseDiff() {
        // Same table, but no shared object actually disagrees - each column gets its own node, neither labeled.
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL);");
        CommitEntry leftAdd = commit("ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);");
        CommitEntry rightAdd = commit("ALTER TABLE orders ADD COLUMN note TEXT;");

        List<String> lines = diff("left", "right", List.of(create, leftAdd), List.of(create, rightAdd));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- note",
                "    |- < ALTER TABLE orders ADD COLUMN note TEXT;",
                "  |- total",
                "    |- > ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);"
        ), lines);
    }

    /**
     * Only the right branch retyped the column; the left carries it exactly as the shared commit left it. The two
     * schemas disagree, but nobody has to choose - which is what separates a merge that can proceed from one that
     * cannot, and what comparing the two sides alone got wrong.
     */
    @Test
    void aColumnOnlyOneSideModifiedIsReportedButNotLabeledAsAConflict() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        CommitEntry rightAlter = commit("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");

        List<String> lines = diff("left", "right", List.of(create), List.of(create, rightAlter));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- total",
                "    |- < ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;"
        ), lines);
    }

    /** The same shape one level down: a constraint redefined on one side only is not a conflict either. */
    @Test
    void aConstraintOnlyOneSideRedefinedIsNotLabeledAsAConflict() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, email TEXT);");
        CommitEntry key = commit("ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (id);");
        CommitEntry drop = commit("ALTER TABLE orders DROP CONSTRAINT orders_key;");
        CommitEntry rightKey = commit("ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (email);");

        List<String> lines = diff("left", "right", List.of(create, key), List.of(create, key, drop, rightKey));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- orders_key (UNIQUE)",
                "    |- < ALTER TABLE orders DROP CONSTRAINT orders_key;",
                "    |- < ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (email);"
        ), lines);
    }

    @Test
    void aConflictingColumnIsLabeledAndListsBothSidesStatementsUnderneathIt() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        CommitEntry leftAlter = commit("ALTER TABLE orders ALTER COLUMN total TYPE INT;");
        CommitEntry rightAlter = commit("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");

        List<String> lines = diff("left", "right", List.of(create, leftAlter), List.of(create, rightAlter));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- total (conflicting)",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE INT;",
                "    |- < ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;"
        ), lines);
    }

    @Test
    void renamingAColumnOnOneSideWhileTheOtherModifiesItIsAConflictByStableIdEvenThoughTheNamesDiffer() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, col1 NUMERIC(10,2));");
        CommitEntry rename = commit("ALTER TABLE orders RENAME COLUMN col1 TO col2;");
        CommitEntry retype = commit("ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;");

        List<String> lines = diff("left", "right", List.of(create, rename), List.of(create, retype));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- col2 (conflicting)",
                "    |- > ALTER TABLE orders RENAME COLUMN col1 TO col2;",
                "    |- < ALTER TABLE orders ALTER COLUMN col1 TYPE BIGINT;"
        ), lines);
    }

    @Test
    void multipleStatementsAgainstTheSameConflictingColumnOnOneSideAreAllListed() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));");
        CommitEntry leftAlter1 = commit("ALTER TABLE orders ALTER COLUMN total TYPE INT;");
        CommitEntry leftAlter2 = commit("ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;");
        CommitEntry rightAlter = commit("ALTER TABLE orders ALTER COLUMN total TYPE NUMERIC(12,4);");

        List<String> lines = diff("left", "right",
                List.of(create, leftAlter1, leftAlter2), List.of(create, rightAlter));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- total (conflicting)",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE INT;",
                "    |- > ALTER TABLE orders ALTER COLUMN total TYPE BIGINT;",
                "    |- < ALTER TABLE orders ALTER COLUMN total TYPE NUMERIC(12,4);"
        ), lines);
    }

    /**
     * A column added and dropped again on one side nets out to no schema difference at all, even though a
     * changeset did touch the table post-divergence - so no {@code "- orders"} node should appear, not even an
     * empty one.
     */
    @Test
    void aTableTouchedPostDivergenceButNettingToNoDifferenceGetsNoNodeAtAll() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL);");
        CommitEntry addColumn = commit("ALTER TABLE orders ADD COLUMN total INT;");
        CommitEntry dropColumn = commit("ALTER TABLE orders DROP COLUMN total;");

        List<String> lines = diff("left", "right",
                List.of(create, addColumn, dropColumn), List.of(create));

        assertEquals(List.of("left vs right"), lines);
    }

    @Test
    void changesBeforeTheCommonAncestorAreExcludedFromTheOutput() {
        ChangeSet createDdl = changeset("CREATE TABLE orders (id INT NOT NULL);");
        ChangeSet divergentLeftDdl = changeset("ALTER TABLE orders ADD COLUMN total INT;");
        CommitEntry create = commit(createDdl);
        CommitEntry divergentLeft = commit(divergentLeftDdl);

        List<String> lines = diff("left", "right", List.of(create, divergentLeft), List.of(create));

        assertEquals(0, lines.stream().filter(line -> line.contains(createDdl.ddl())).count());
        assertEquals(1, lines.stream().filter(line -> line.contains(divergentLeftDdl.ddl())).count());
    }

    @Test
    void aConstraintAddedOnOneSideGetsItsOwnNodeLabeledWithItsKind() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL);");
        CommitEntry addKey = commit("ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);");

        List<String> lines = diff("main", "feature", List.of(create, addKey), List.of(create));

        assertEquals(List.of(
                "main vs feature",
                "- orders",
                "  |- orders_pkey (PRIMARY KEY)",
                "    |- > ALTER TABLE orders ADD CONSTRAINT orders_pkey PRIMARY KEY (id);"
        ), lines);
    }

    @Test
    void anIndexAddedOnOneSideGetsItsOwnNodeToo() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, total INT);");
        CommitEntry addIndex = commit("CREATE UNIQUE INDEX idx_orders_total ON orders (total);");

        List<String> lines = diff("main", "feature", List.of(create, addIndex), List.of(create));

        assertEquals(List.of(
                "main vs feature",
                "- orders",
                "  |- idx_orders_total (UNIQUE INDEX)",
                "    |- > CREATE UNIQUE INDEX idx_orders_total ON orders (total);"
        ), lines);
    }

    @Test
    void thesameConstraintNameDefinedDifferentlyOnEachSideIsLabeledConflicting() {
        CommitEntry create = commit("CREATE TABLE orders (id INT NOT NULL, email TEXT);");
        CommitEntry leftKey = commit("ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (id);");
        CommitEntry rightKey = commit("ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (email);");

        List<String> lines = diff("left", "right", List.of(create, leftKey), List.of(create, rightKey));

        assertEquals(List.of(
                "left vs right",
                "- orders",
                "  |- orders_key (UNIQUE) (conflicting)",
                "    |- > ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (id);",
                "    |- < ALTER TABLE orders ADD CONSTRAINT orders_key UNIQUE (email);"
        ), lines);
    }

    private List<String> diff(String left, String right, List<CommitEntry> leftCommits, List<CommitEntry> rightCommits) {
        return formatter.format(left, right, differ.diff(leftCommits, rightCommits));
    }

    private ChangeSet changeset(String ddl) {
        return new ChangeSet(changesetIdSequence++, "test", ddl, ChangesetStatus.COMMIT, Instant.now());
    }

    /** One commit carrying a single changeset - fine-grained enough that reusing the same {@link CommitEntry}
     * instance on both sides is exactly "this commit is shared", matching the old prefix-based tests' granularity. */
    private CommitEntry commit(ChangeSet changeset) {
        return new CommitEntry(new Commit(commitIdSequence++, "test", null, Instant.now(), null), List.of(changeset));
    }

    private CommitEntry commit(String ddl) {
        return commit(changeset(ddl));
    }
}
