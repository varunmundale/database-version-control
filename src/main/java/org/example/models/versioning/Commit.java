package org.example.models.versioning;

import java.time.Instant;
import java.util.Objects;

/**
 * One commit as stored: its identity and place in the shared graph, the branch it was created on, plus who made
 * it and why. {@code branch} is where it was *made*, not where it's visible - the graph is shared across forks and
 * merges, so this is the only thing that says which commits in a branch's history are its own.
 */
public record Commit(long id, String branch, CommitMetadata metadata, Instant createdAt, CommitParents parents) {
    private static final String UNKNOWN_BRANCH = "unknown";

    public Commit {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        branch = branch == null || branch.isBlank() ? UNKNOWN_BRANCH : branch.strip();
        metadata = metadata == null ? new CommitMetadata(null, null) : metadata;
        parents = parents == null ? new CommitParents(null, null) : parents;
    }

    public String author() {
        return metadata.author();
    }

    public String message() {
        return metadata.message();
    }

    /** True when this commit joined another branch's history in rather than introducing changesets of its own. */
    public boolean isMerge() {
        return parents.secondParentCommitId() != null;
    }
}
