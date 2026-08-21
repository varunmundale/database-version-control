package org.example.core;

import java.util.List;

/** The outcome of attempting to merge one branch's diverged history into another via {@link BranchMerger}. */
public sealed interface MergeResult {
    /** The target branch already contains everything the other branch has - nothing was done. */
    record AlreadyUpToDate() implements MergeResult {
    }

    /** The two branches touched the same column incompatibly since they diverged, so the merge was rejected. Each entry describes one conflicting table/column pair. */
    record Conflict(List<String> conflicts) implements MergeResult {
    }

    /** The merge commit was created. {@code stagingBranch} is the scratch branch used to validate the replay before it was applied to the target branch's own database. */
    record Success(long commitId, String stagingBranch, int appliedChangesetCount) implements MergeResult {
    }
}
