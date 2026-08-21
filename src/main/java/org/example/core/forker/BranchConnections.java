package org.example.core.forker;

import org.example.config.BranchDatabaseConfig;
import org.example.config.ConnectionSettings;
import org.example.repository.DbGitLocalRepository;

import java.util.Objects;

/**
 * Decides which database a branch's DDL is actually applied to. Two different answers, and the whole point of
 * {@code dbgit init} is the first one:
 *
 * <ul>
 *   <li>{@code main} tracks a real, pre-existing database - the one this workspace was initialised against. Its
 *       connection, credentials included, comes from the workspace's own {@code .dbgit/config.json}.</li>
 *   <li>every other branch is a fork living in the shared scratchpad container, addressed by a sanitized name.</li>
 * </ul>
 *
 * <p>{@code main} therefore only works once initialised; before that there is no database to point at, and saying
 * so is better than quietly writing to the scratchpad as this used to.
 */
public final class BranchConnections {
    private static final String DEFAULT_BRANCH = "main";

    private final BranchDatabaseConfig config;
    private final DbGitLocalRepository localRepository;

    public BranchConnections(BranchDatabaseConfig config, DbGitLocalRepository localRepository) {
        this.config = Objects.requireNonNull(config, "config must not be null");
        this.localRepository = Objects.requireNonNull(localRepository, "localRepository must not be null");
    }

    public ConnectionSettings forBranch(String branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        if (!branch.equals(DEFAULT_BRANCH)) {
            return config.connectionTo(forkedDatabaseName(branch));
        }
        return localRepository.trackedConnection(DEFAULT_BRANCH).orElseThrow(() -> new IllegalStateException(
                "Branch 'main' is not tracking a database yet. Run 'dbgit init' with the connection details of the"
                        + " database it should track, e.g. dbgit init --host localhost --port 5432"
                        + " --database app --user postgres --password secret"));
    }

    /** True when {@code main} has been pointed at a database in this workspace. */
    public boolean isInitialized() {
        return localRepository.trackedConnection(DEFAULT_BRANCH).isPresent();
    }

    /** The scratchpad database name a forked branch lives under. {@code main} never has one - it is not a fork. */
    public static String forkedDatabaseName(String branch) {
        if (branch.equals(DEFAULT_BRANCH)) {
            throw new IllegalArgumentException("main is not a forked branch; it tracks a database of its own");
        }
        String readable = branch.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        return (readable.length() > 40 ? readable.substring(0, 40) : readable) + "_postgres";
    }
}
