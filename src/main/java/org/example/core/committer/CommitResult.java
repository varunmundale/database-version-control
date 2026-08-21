package org.example.core.committer;

/** The outcome of attempting to commit a branch's currently APPLIED changesets via {@link Committer}. */
public sealed interface CommitResult {
    /** The branch had no APPLIED changesets to fold into a commit - nothing was done. */
    record NothingToCommit() implements CommitResult {
    }

    /** The commit was created, folding in {@code changesetCount} previously APPLIED changesets. */
    record Success(long commitId, int changesetCount) implements CommitResult {
    }
}
