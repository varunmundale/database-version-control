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
 * turn a HEAD commit id into a branch's own history by walking parent edges.
 */
public final class CommitGraph {
    private final Map<Long, Commit> commitsById;

    public CommitGraph(Map<Long, Commit> commitsById) {
        this.commitsById = commitsById;
    }

    /**
     * Root-first topological order of the sub-DAG reachable from {@code headCommitId}: a branch's schema was built
     * up as the first parent's full history, then anything reachable only through a merge commit's second parent,
     * then the commit itself. A common ancestor reachable through both parents is visited once.
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
