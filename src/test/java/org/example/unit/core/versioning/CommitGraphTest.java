package org.example.unit.core.versioning;

import org.example.core.versioning.CommitGraph;
import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitMetadata;
import org.example.models.versioning.CommitParents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The DAG traversals extracted out of {@code MetadataVersioningService}, tested against a hand-built commit map. */
class CommitGraphTest {
    @Test
    void aNullHeadHasNoHistory() {
        CommitGraph graph = new CommitGraph(Map.of());

        assertTrue(graph.topologicalOrder(null).isEmpty());
    }

    @Test
    void linearHistoryComesBackRootToHead() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        commits.put(2L, commit(2L, 1L, null));
        commits.put(3L, commit(3L, 2L, null));
        CommitGraph graph = new CommitGraph(commits);

        assertEquals(List.of(1L, 2L, 3L), graph.topologicalOrder(3L));
    }

    /**
     * A merge commit's second parent contributes whatever it diverged with, and that content is ordered after the
     * first parent's own history - mirroring how a merge physically replays the second branch's changesets on top
     * of the first's.
     */
    @Test
    void aMergeCommitOrdersTheFirstParentsHistoryBeforeTheSecondsOwnContribution() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        commits.put(2L, commit(2L, 1L, null));
        commits.put(3L, commit(3L, 1L, null));
        commits.put(4L, commit(4L, 2L, 3L));
        CommitGraph graph = new CommitGraph(commits);

        assertEquals(List.of(1L, 2L, 3L, 4L), graph.topologicalOrder(4L));
    }

    /** A commit reachable through both parents (a common ancestor) is emitted once, at its first-reached position. */
    @Test
    void aCommonAncestorReachedThroughBothParentsIsNotDuplicated() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        commits.put(2L, commit(2L, 1L, null));
        commits.put(3L, commit(3L, 1L, null));
        commits.put(4L, commit(4L, 2L, 3L));
        CommitGraph graph = new CommitGraph(commits);

        List<Long> order = graph.topologicalOrder(4L);

        assertEquals(1, order.stream().filter(id -> id == 1L).count(), order.toString());
    }

    @Test
    void aCommitIsItsOwnAncestor() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        CommitGraph graph = new CommitGraph(commits);

        assertTrue(graph.isAncestor(1L, 1L));
    }

    @Test
    void aCommitReachableOnlyThroughTheSecondParentIsStillAnAncestor() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        commits.put(2L, commit(2L, 1L, null));
        commits.put(3L, commit(3L, 1L, null));
        commits.put(4L, commit(4L, 2L, 3L));
        CommitGraph graph = new CommitGraph(commits);

        assertTrue(graph.isAncestor(3L, 4L));
    }

    @Test
    void aCommitOnAnUnrelatedBranchIsNotAnAncestor() {
        Map<Long, Commit> commits = new LinkedHashMap<>();
        commits.put(1L, commit(1L, null, null));
        commits.put(2L, commit(2L, 1L, null));
        commits.put(3L, commit(3L, 1L, null));
        CommitGraph graph = new CommitGraph(commits);

        assertFalse(graph.isAncestor(2L, 3L));
    }

    private static Commit commit(long id, Long parent, Long secondParent) {
        return new Commit(id, "test", new CommitMetadata("tester", "message"), Instant.EPOCH,
                new CommitParents(parent, secondParent));
    }
}
