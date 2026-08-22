package org.example.core.log;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.CommitEntry;

import java.util.List;
import java.util.Objects;

/**
 * Everything {@code dbgit log} has to say about one branch: the changesets lying around uncommitted, and the
 * commits behind them - newest first, the way a log reads.
 *
 * @param workingSet staged changesets no commit has claimed yet, in the order they were staged
 * @param commits    the branch's commits from HEAD backwards, each with the changesets folded into it
 */
public record BranchLog(String branch, List<ChangeSet> workingSet, List<CommitEntry> commits) {
    public BranchLog {
        Objects.requireNonNull(branch, "branch must not be null");
        workingSet = List.copyOf(workingSet);
        commits = List.copyOf(commits);
    }

    public boolean isEmpty() {
        return workingSet.isEmpty() && commits.isEmpty();
    }
}
