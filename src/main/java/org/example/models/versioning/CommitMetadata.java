package org.example.models.versioning;

/**
 * Who made a commit and why - the part of a commit a person writes, as opposed to the parent pointers dbgit
 * derives. Supplied by the command line ({@code dbgit commit -m "..."}) and defaulted here when it isn't, so no
 * caller has to decide what an absent author or message means.
 */
public record CommitMetadata(String author, String message) {
    private static final String UNKNOWN_AUTHOR = "unknown";

    public CommitMetadata {
        author = author == null || author.isBlank() ? UNKNOWN_AUTHOR : author.strip();
        message = message == null ? "" : message.strip();
    }

    /** Attributes the commit to whoever the daemon is running as, which is the best answer available locally. */
    public static CommitMetadata by(String message) {
        return new CommitMetadata(System.getProperty("user.name"), message);
    }
}
