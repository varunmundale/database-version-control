package org.example.core.merger;

import java.util.List;

/** The outcome of attempting to merge one branch's diverged history into another via {@link Merger}. */
public sealed interface MergeResult {
    /** The target branch already contains everything the other branch has - nothing was done. */
    record AlreadyUpToDate() implements MergeResult {
    }

    /**
     * The two branches touched the same table, column, constraint or index incompatibly since they diverged, so
     * the merge was rejected. Each entry describes one conflicting object. Resolution is manual, the same way
     * {@code dbgit add} stages any other change: add a compensating DDL statement to either branch that reconciles
     * the conflicting change (so the two branches' replayed schemas agree again for that object), commit it, and
     * retry the merge - the conflict check will no longer see a difference to flag.
     */
    record Conflict(List<String> conflicts) implements MergeResult {
    }

    /** The merge commit was created. {@code stagingBranch} is the scratch branch used to validate the replay before it was applied to the target branch's own database. */
    record Success(long commitId, String stagingBranch, int appliedChangesetCount) implements MergeResult {
    }
}
