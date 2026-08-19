package org.example.commands;

import java.time.Instant;
import java.util.Objects;

/** One raw DDL statement staged for a branch, and where it is in its PENDING -> APPLIED -> COMMIT lifecycle. */
public record ChangeSet(long id, String branch, String ddl, ChangesetStatus status, Instant appliedAt) {
    public ChangeSet {
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(ddl, "ddl must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(appliedAt, "appliedAt must not be null");
    }
}
