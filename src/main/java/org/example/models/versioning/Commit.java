package org.example.models.versioning;

import java.time.Instant;
import java.util.Objects;

/**
 * One commit as it is stored: its identity and place in the shared graph, the branch it was created on, plus who
 * created it and why. Commits predating branch/author/message tracking read back with the same defaults an
 * unattributed commit gets, so nothing downstream has to handle a null.
 *
 * <p>{@code branch} is where the commit was made, not where it can be seen: the graph is shared, so a fork
 * inherits its parent's commits and a merge brings in commits made on a branch that may since have been deleted.
 * That is exactly what makes it worth recording - it is the only thing that says which of the commits in a
 * branch's history are its own.
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
