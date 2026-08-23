package org.example.protocol;

import org.example.config.ConnectionSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * The author to attribute this request's work to, which branch they are on, and how to reach the database
 * {@code main} tracks - everything the daemon used to read from its own working directory and now receives per
 * request instead.
 *
 * <p>That inversion is what makes the daemon multi-user. {@code .dbgit/HEAD} was a single file in the daemon's
 * process directory, so every client shared one checked-out branch; now each workspace keeps its own and says
 * which it means. The tracked connection travels the same way, so the daemon holds no credential of its own and
 * each caller reaches the tracked database as themselves.
 *
 * @param trackedDatabase {@code null} until the client's workspace has run {@code dbgit init}
 */
public record RequestContext(String author, String branch, ConnectionSettings trackedDatabase) {
    public static final String DEFAULT_BRANCH = "main";
    private static final String UNKNOWN_AUTHOR = "unknown";

    public RequestContext {
        author = author == null || author.isBlank() ? UNKNOWN_AUTHOR : author.strip();
        branch = branch == null || branch.isBlank() ? DEFAULT_BRANCH : branch.strip();
    }

    public Optional<ConnectionSettings> trackedDatabaseIfConfigured() {
        return Optional.ofNullable(trackedDatabase);
    }

    public RequestContext onBranch(String otherBranch) {
        return new RequestContext(author, Objects.requireNonNull(otherBranch, "branch must not be null"), trackedDatabase);
    }
}
