package org.example.toygit;

/** A single file-level change produced by {@link ToyGit#diff(Commit, Commit)}. */
public record FileChange(String path, ChangeType type, String oldContent, String newContent) {
}
