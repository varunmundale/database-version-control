package org.example.models.versioning;

/**
 * One commit's place in the shared commit graph: its first parent, and - for a merge commit only - the second
 * parent whose branch it brought in. Both are {@code null} for a root commit.
 */
public record CommitParents(Long parentCommitId, Long secondParentCommitId) {
}
