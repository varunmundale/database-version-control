package org.example.core.forker;

import org.example.core.forker.docker.CommandRunner;
import org.example.core.forker.docker.ProcessCommandRunner;
import org.example.core.forker.docker.SharedPostgresContainer;
import org.example.config.BranchDatabaseConfig;
import org.example.connectors.ConnectorFactory;
import org.example.models.versioning.ChangeSet;
import org.example.repository.BranchDatabaseRepository;
import org.example.core.versioning.MetadataVersioningService;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Forks a branch's database into one persistent PostgreSQL Docker container, recreating it from the parent's commit history. */
public final class Forker {
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    /** The one dialect {@link SharedPostgresContainer} actually starts anything for. */
    private static final String POSTGRESQL_DIALECT = "postgresql";

    private final BranchDatabaseConfig config;
    private final BranchDatabaseRepository branchDatabases;
    private final VersioningService versioningService;
    private final SharedPostgresContainer sharedContainer;

    public Forker() {
        this(new ProcessCommandRunner(), BranchDatabaseRepository.getInstance(), new MetadataVersioningService());
    }

    /** Builds the branch-database repository over a supplied connector, so tests can fork without a real container. */
    public Forker(CommandRunner commandRunner, ConnectorFactory connectorFactory, VersioningService versioningService) {
        this(commandRunner, BranchDatabaseConfig.getInstance(), connectorFactory, versioningService);
    }

    /**
     * As above, but with the config supplied rather than read from {@code dbgit.json}'s classpath singleton - what a
     * test asserting on {@link SharedPostgresContainer}'s real docker commands (container name, host port, dialect)
     * uses, so it depends on a config of its own rather than on whatever the shared test {@code dbgit.json} says.
     */
    public Forker(CommandRunner commandRunner, BranchDatabaseConfig config, ConnectorFactory connectorFactory,
                  VersioningService versioningService) {
        this(commandRunner, config, new BranchDatabaseRepository(config, connectorFactory), versioningService);
    }

    public Forker(CommandRunner commandRunner, BranchDatabaseRepository branchDatabases, VersioningService versioningService) {
        this(commandRunner, BranchDatabaseConfig.getInstance(), branchDatabases, versioningService);
    }

    /** The designated constructor: every other one funnels through here with whatever config it settled on. */
    public Forker(CommandRunner commandRunner, BranchDatabaseConfig config, BranchDatabaseRepository branchDatabases,
                  VersioningService versioningService) {
        this.config = Objects.requireNonNull(config, "config must not be null");
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

    /**
     * Brings the shared scratchpad container up and waits until it answers - the precondition for creating any
     * branch database, whether {@link #fork} is building a new one or {@code dbgit reset} is rebuilding one.
     *
     * <p>A no-op for any dialect but {@code postgresql}: an in-memory H2 database needs no server, no container and
     * no Docker at all, so starting one here would be pure waste - and the shared container this brings up is,
     * by name and by the docker commands it runs, a PostgreSQL-specific thing regardless of {@code dialect}.
     */
    public void ensureBranchDatabasesRunning() {
        if (config.dialect().equals(POSTGRESQL_DIALECT)) {
            sharedContainer.ensureRunning();
        }
    }

    public ForkResult fork(String fromBranch, String currentBranch) {
        validateBranch(fromBranch, "fromBranch");
        validateBranch(currentBranch, "currentBranch");

        if (!versioningService.createBranch(currentBranch, fromBranch)) {
            throw fail("Branch already exists: " + currentBranch, null);
        }

        ensureBranchDatabasesRunning();

        String database = BranchConnections.forkedDatabaseName(currentBranch);
        List<ChangeSet> history = versioningService.commitHistory(currentBranch);
        try {
            out("Creating database '" + database + "' for branch '" + currentBranch + "'.");
            branchDatabases.createDatabase(database);

            out("Recreating branch '" + currentBranch + "' from " + history.size() + " committed changeset(s) shared with '" + fromBranch + "'.");
            branchDatabases.replay(database, history);
        } catch (RuntimeException exception) {
            // The name was claimed before any of this ran. Leaving it claimed would wedge it permanently:
            // the branch would list, its database would not exist, and forking it again would be refused as
            // already existing. Nothing has been staged on it yet, so giving it back is safe.
            abandon(currentBranch, database);
            throw exception instanceof ForkException forked ? forked : fail(exception.getMessage(), exception);
        }

        out("Branch fork completed for '" + currentBranch + "'.");
        return new ForkResult(fromBranch, currentBranch, config.containerName(), database);
    }

    /** Undoes a half-built fork, best-effort: a failure here must not mask the failure that caused it. */
    private void abandon(String branch, String database) {
        try {
            branchDatabases.dropDatabase(database);
        } catch (RuntimeException ignored) {
            err("Could not drop the partially created database '" + database + "'.");
        }
        try {
            versioningService.deleteBranch(branch);
        } catch (RuntimeException ignored) {
            err("Could not release the branch name '" + branch + "'; it may need removing by hand.");
        }
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
