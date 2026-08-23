package org.example.service.command;

import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.locking.BranchLocks;
import org.example.core.replayer.Replayer;
import org.example.core.versioning.VersioningService;
import org.example.request.RequestContext;

import java.util.Objects;

/**
 * Everything a {@link Command} needs to run: the daemon's shared collaborators, plus the {@link RequestContext}
 * of the one request being served.
 *
 * <p>The collaborators are built once and shared by every thread; the request half is rebuilt per request via
 * {@link #forRequest}. That split is what makes the daemon multi-user - the branch a command acts on is the
 * caller's, not a process-wide file's.
 */
public record CommandContext(Forker forker, Replayer replayer, BranchConnections connections,
                             BranchLocks locks, RequestContext request) {
    public CommandContext {
        Objects.requireNonNull(forker, "forker must not be null");
        Objects.requireNonNull(replayer, "replayer must not be null");
        Objects.requireNonNull(connections, "connections must not be null");
        Objects.requireNonNull(locks, "locks must not be null");
        Objects.requireNonNull(request, "request must not be null");
    }

    /** The same shared collaborators, bound to another request. */
    public CommandContext forRequest(RequestContext other) {
        return new CommandContext(forker, replayer, connections, locks, other);
    }

    /** The branch this request is acting on - what {@code .dbgit/HEAD} used to answer, per caller now. */
    public String branch() {
        return request.branch();
    }

    /** The versioning service every branch-aware command reads and writes history through. */
    public VersioningService versioningService() {
        return forker.versioningService();
    }
}
