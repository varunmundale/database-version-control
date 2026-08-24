package org.example.request;

import org.example.config.ConnectionSettings;

import java.util.Objects;
import java.util.Optional;

/**
 * The author to attribute this request's work to, which branch they are on, and how to reach the database
 * {@code main} tracks - everything the daemon needs per request rather than reading from its own state, which is
 * what makes it multi-user: the daemon holds no branch or credential of its own.
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
