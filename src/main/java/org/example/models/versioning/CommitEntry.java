package org.example.models.versioning;

import java.util.List;
import java.util.Objects;

/**
 * A commit together with the changesets folded into it - what {@code dbgit log} shows as one entry, and what a
 * history replay walks to rebuild a schema. A merge commit carries none of its own: the changesets it brings in
 * stay attributed to the commits that originally introduced them.
 */
public record CommitEntry(Commit commit, List<ChangeSet> changesets) {
    public CommitEntry {
        Objects.requireNonNull(commit, "commit must not be null");
        changesets = List.copyOf(changesets);
    }

    public long commitId() {
        return commit.id();
    }
}
