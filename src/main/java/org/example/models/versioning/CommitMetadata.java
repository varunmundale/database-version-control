package org.example.models.versioning;

/**
 * Who made a commit and why - the part of a commit a person writes, as opposed to the parent pointers dbgit
 * derives. The author is the request's own user: the caller's workspace's configured identity ({@code dbgit init
 * --author}) if one was set, otherwise whoever is running {@code dbgit} on the caller's machine - never the
 * daemon's. Defaulted here when either piece is blank, so no caller has to decide what an absent author or
 * message means.
 */
public record CommitMetadata(String author, String message) {
    private static final String UNKNOWN_AUTHOR = "unknown";

    public CommitMetadata {
        author = author == null || author.isBlank() ? UNKNOWN_AUTHOR : author.strip();
        message = message == null ? "" : message.strip();
    }
}
