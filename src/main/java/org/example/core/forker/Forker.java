package org.example.core.forker;

import org.example.core.forker.docker.CommandRunner;
import org.example.core.forker.docker.ProcessCommandRunner;
import org.example.core.forker.docker.SharedPostgresContainer;
import org.example.config.BranchDatabaseConfig;
import org.example.connectors.ConnectorFactory;
import org.example.models.versioning.ChangeSet;
import org.example.repository.BranchDatabaseRepository;
import org.example.repository.RepositoryException;
import org.example.core.versioning.MetadataVersioningService;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Forks a branch's database into one persistent PostgreSQL Docker container, recreating it from the parent's commit history. */
public final class Forker {
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");

    private final BranchDatabaseConfig config;
    private final BranchDatabaseRepository branchDatabases;
    private final VersioningService versioningService;
    private final SharedPostgresContainer sharedContainer;

    public Forker() {
        this(new ProcessCommandRunner(), BranchDatabaseRepository.getInstance(), new MetadataVersioningService());
    }

    /** Builds the branch-database repository over a supplied connector, so tests can fork without a real container. */
    public Forker(CommandRunner commandRunner, ConnectorFactory connectorFactory, VersioningService versioningService) {
        this(commandRunner, new BranchDatabaseRepository(BranchDatabaseConfig.getInstance(), connectorFactory), versioningService);
    }

    public Forker(CommandRunner commandRunner, BranchDatabaseRepository branchDatabases, VersioningService versioningService) {
        this.config = BranchDatabaseConfig.getInstance();
        this.branchDatabases = Objects.requireNonNull(branchDatabases, "branchDatabases must not be null");
        this.versioningService = Objects.requireNonNull(versioningService, "versioningService must not be null");
        this.sharedContainer = new SharedPostgresContainer(
                Objects.requireNonNull(commandRunner, "commandRunner must not be null"), config, this.branchDatabases);
    }

    public VersioningService versioningService() {
        return versioningService;
    }

    /** The branch databases this forker materializes into, for callers that have to run DDL against one. */
    public BranchDatabaseRepository branchDatabases() {
        return branchDatabases;
    }

    public ForkResult fork(String fromBranch, String currentBranch) {
        validateBranch(fromBranch, "fromBranch");
        validateBranch(currentBranch, "currentBranch");

        if (!versioningService.createBranch(currentBranch, fromBranch)) {
            throw fail("Branch already exists: " + currentBranch, null);
        }

        sharedContainer.ensureRunning();

        String database = BranchConnections.forkedDatabaseName(currentBranch);
        List<ChangeSet> history = versioningService.commitHistory(currentBranch);
        try {
            out("Creating database '" + database + "' for branch '" + currentBranch + "'.");
            branchDatabases.createDatabase(database);

            out("Recreating branch '" + currentBranch + "' from " + history.size() + " committed changeset(s) shared with '" + fromBranch + "'.");
            branchDatabases.replay(database, history);
        } catch (RepositoryException exception) {
            throw fail(exception.getMessage(), exception);
        }

        out("Branch fork completed for '" + currentBranch + "'.");
        return new ForkResult(fromBranch, currentBranch, config.containerName(), database);
    }

    private static void validateBranch(String branch, String argumentName) {
        if (branch == null || !BRANCH_NAME.matcher(branch).matches()) {
            throw new IllegalArgumentException(argumentName + " must be a non-empty Git-style branch name");
        }
    }

    private static ForkException fail(String message, Throwable cause) {
        err(message);
        return cause == null ? new ForkException(message) : new ForkException(message, cause);
    }

    private static void out(String message) {
        System.out.println("[Forker] " + message);
        System.out.flush();
    }

    private static void err(String message) {
        System.err.println("[Forker] " + message);
        System.err.flush();
    }
}
