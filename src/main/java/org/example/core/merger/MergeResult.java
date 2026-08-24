package org.example.core.merger;

import java.util.List;

/** The outcome of attempting to merge one branch's diverged history into another via {@link Merger}. */
public sealed interface MergeResult {
    /** The target branch already contains everything the other branch has - nothing was done. */
    record AlreadyUpToDate() implements MergeResult {
    }

    /**
     * The two branches touched the same table, column, constraint or index incompatibly since they diverged.
     * Resolve by committing a compensating DDL statement on either branch and retrying the merge.
     */
    record Conflict(List<String> conflicts) implements MergeResult {
    }

    /** The merge commit was created; {@code stagingBranch} is the scratch branch used to validate the replay first. */
    record Success(long commitId, String stagingBranch, int appliedChangesetCount) implements MergeResult {
    }
}
