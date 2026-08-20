package org.example.model.versioning;

/** Lifecycle of one changeset: staged, executed against the branch's database, then folded into a commit. */
public enum ChangesetStatus {
    PENDING, APPLIED, COMMIT
}
