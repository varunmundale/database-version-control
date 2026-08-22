package org.example.models.versioning;

import java.time.Instant;
import java.util.Objects;

/**
 * One commit as it is stored: its identity and place in the shared graph, plus who created it and why. Commits
 * predating author/message tracking read back with the same defaults an unattributed commit gets, so nothing
 * downstream has to handle a null.
 */
public record Commit(long id, CommitMetadata metadata, Instant createdAt, CommitParents parents) {
    public Commit {
        Objects.requireNonNull(createdAt, "createdAt must not be null");
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
