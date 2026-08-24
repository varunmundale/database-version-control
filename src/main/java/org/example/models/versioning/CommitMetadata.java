package org.example.models.versioning;

/**
 * Who made a commit and why - the part a person writes, as opposed to the parent pointers dbgit derives. The
 * author is the caller workspace's configured identity ({@code dbgit init --author}), or {@code "unknown"}.
 */
public record CommitMetadata(String author, String message) {
    private static final String UNKNOWN_AUTHOR = "unknown";

    public CommitMetadata {
        author = author == null || author.isBlank() ? UNKNOWN_AUTHOR : author.strip();
        message = message == null ? "" : message.strip();
    }
}
