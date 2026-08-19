package org.example.branch;

import java.time.Instant;
import java.util.Objects;

/** One raw DDL statement applied to a branch. */
public record ChangeSet(String branch, String ddl, Instant appliedAt) {
    public ChangeSet {
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(ddl, "ddl must not be null");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");
    }
}
