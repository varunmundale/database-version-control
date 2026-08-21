package org.example.service.command;

import org.example.branch.BranchFork;
import org.example.branch.DbGitRepository;
import org.example.core.SchemaReplayer;

import java.util.Objects;

/** Everything a {@link Command} needs to run, assembled once by {@code DbGitService} and handed to each command it builds. */
public record CommandContext(DbGitRepository repository, BranchFork branchFork, SchemaReplayer schemaReplayer) {
    public CommandContext {
        Objects.requireNonNull(repository, "repository must not be null");
        Objects.requireNonNull(branchFork, "branchFork must not be null");
        Objects.requireNonNull(schemaReplayer, "schemaReplayer must not be null");
    }
}
