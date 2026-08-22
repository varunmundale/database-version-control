package org.example.core.forker;

import org.example.config.BranchDatabaseConfig;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;

import java.util.Objects;

/**
 * Decides which database a branch's DDL is actually applied to. Two different answers:
 *
 * <ul>
 *   <li>{@code main} tracks a real, pre-existing database - the one the caller's workspace was initialised
 *       against. Its connection, credentials included, arrives on the request.</li>
 *   <li>every other branch is a fork living in the shared scratchpad container, addressed by a sanitized name.</li>
 * </ul>
 *
 * <p>The connection for {@code main} comes from the {@link RequestContext} rather than from any file the daemon
 * owns, which is what lets two users of one daemon reach two different tracked databases - each as themselves.
 * Before that workspace has run {@code dbgit init} there is nothing to point at, and saying so is better than
 * quietly writing to the scratchpad.
 */
public final class BranchConnections {
    private final BranchDatabaseConfig config;

    public BranchConnections(BranchDatabaseConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public ConnectionSettings forBranch(RequestContext request, String branch) {
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(branch, "branch must not be null");
        if (!branch.equals(RequestContext.DEFAULT_BRANCH)) {
            return config.connectionTo(forkedDatabaseName(branch));
        }
        return request.trackedDatabaseIfConfigured().orElseThrow(() -> new IllegalStateException(
                "Branch 'main' is not tracking a database yet. Run 'dbgit init' with the connection details of the"
                        + " database it should track, e.g. dbgit init --host localhost --port 5432"
                        + " --database app --user postgres --password secret"));
    }

    /** The scratchpad database name a forked branch lives under. {@code main} never has one - it is not a fork. */
    public static String forkedDatabaseName(String branch) {
        if (branch.equals(RequestContext.DEFAULT_BRANCH)) {
            throw new IllegalArgumentException("main is not a forked branch; it tracks a database of its own");
        }
        String readable = branch.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        return (readable.length() > 40 ? readable.substring(0, 40) : readable) + "_postgres";
    }
}
