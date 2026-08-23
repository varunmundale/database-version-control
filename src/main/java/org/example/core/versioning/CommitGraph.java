package org.example.core.versioning;

import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitParents;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The shared commit DAG - every commit ever created, across every branch - keyed by id, plus the traversals that
 * turn a HEAD commit id into a branch's own history. Every edge here points from a commit to its parent(s), so a
 * branch's ancestry is exactly the sub-DAG reachable by walking those edges from its HEAD.
 */
public final class CommitGraph {
    private final Map<Long, Commit> commitsById;

    public CommitGraph(Map<Long, Commit> commitsById) {
        this.commitsById = commitsById;
    }

    /**
     * The topological order of the sub-DAG reachable from {@code headCommitId} - every parent before every child,
     * root first - which is exactly the order a branch's schema was actually built up in: the first parent's full
     * history, then anything reachable only through the second parent (a merge commit's contribution), then the
     * commit itself, mirroring how a merge commit physically replayed the second branch's diverged changesets on
     * top of the first's. A node reachable through both parents (a common ancestor) is visited once, at the
     * position it is first reached.
     */
    public List<Long> topologicalOrder(Long headCommitId) {
        List<Long> order = new ArrayList<>();
        visit(headCommitId, new LinkedHashSet<>(), order);
        return order;
    }

    /** Whether {@code candidateId} is {@code headCommitId} itself or reachable from it by walking parent edges. */
    public boolean isAncestor(long candidateId, Long headCommitId) {
        return isAncestor(candidateId, headCommitId, new HashSet<>());
    }

    private void visit(Long commitId, Set<Long> visited, List<Long> order) {
        if (commitId == null || visited.contains(commitId)) {
            return;
        }
        CommitParents parents = commitsById.get(commitId).parents();
        visit(parents.parentCommitId(), visited, order);
        visit(parents.secondParentCommitId(), visited, order);
        if (visited.add(commitId)) {
            order.add(commitId);
        }
    }

    private boolean isAncestor(long candidateId, Long commitId, Set<Long> visited) {
        if (commitId == null) {
            return false;
        }
        if (commitId == candidateId) {
            return true;
        }
        if (!visited.add(commitId)) {
            return false;
        }
        CommitParents parents = commitsById.get(commitId).parents();
        return isAncestor(candidateId, parents.parentCommitId(), visited)
                || isAncestor(candidateId, parents.secondParentCommitId(), visited);
    }
}
