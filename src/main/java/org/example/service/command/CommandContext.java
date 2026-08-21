package org.example.service.command;

import org.example.core.forker.Forker;
import org.example.repository.DbGitLocalRepository;
import org.example.core.replayer.Replayer;
import org.example.core.versioning.VersioningService;

import java.util.Objects;

/** Everything a {@link Command} needs to run, assembled once by {@code DbGitCommandListener} and handed to each command it builds. */
public record CommandContext(DbGitLocalRepository repository, Forker forker, Replayer replayer) {
    public CommandContext {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(forker, "forker must not be null");
        Objects.requireNonNull(replayer, "replayer must not be null");
    }

    /** The versioning service every branch-aware command reads and writes history through. */
    public VersioningService versioningService() {
        return forker.versioningService();
    }
}
