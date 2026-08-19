package org.example.branch;

import org.example.database.PostgresConnector;
import org.example.database.SqlConnector;

import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/** Forks a branch's databases, as copies, into one persistent PostgreSQL Docker container. */
public final class BranchFork {
    private static final Pattern BRANCH_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]*");
    private static final int CONTAINER_PORT = 5432;
    private static final int READY_ATTEMPTS = 20;
    private static final String POSTGRES_LOGICAL_NAME = "postgres";
    private static final String DEFAULT_BRANCH = "main";

    private final CommandRunner commandRunner;
    private final PostgresDockerConfig config;
    private final PostgresConnectorFactory connectorFactory;
    private final BranchMetadataStore metadataStore;

    public BranchFork() {
        this(new ProcessCommandRunner());
    }

    public BranchFork(CommandRunner commandRunner) {
        this(commandRunner, defaultConnectorFactory(PostgresDockerConfig.getInstance()), defaultMetadataStore());
    }

    public BranchFork(CommandRunner commandRunner, PostgresConnectorFactory connectorFactory) {
        this(commandRunner, connectorFactory, defaultMetadataStore());
    }

    public BranchFork(CommandRunner commandRunner, PostgresConnectorFactory connectorFactory, BranchMetadataStore metadataStore) {
        this.commandRunner = Objects.requireNonNull(commandRunner, "commandRunner must not be null");
        this.config = PostgresDockerConfig.getInstance();
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
        this.metadataStore = Objects.requireNonNull(metadataStore, "metadataStore must not be null");
    }

    public BranchMetadataStore metadataStore() {
        return metadataStore;
    }

    /** The database a branch's own DDL is applied against: the shared admin database for {@code main}, its forked copy otherwise. */
    public String defaultDatabaseName(String branch) {
        if (branch.equals(DEFAULT_BRANCH)) {
            return config.adminDatabase();
        }
        return sanitizeBranchName(branch) + "_" + POSTGRES_LOGICAL_NAME;
    }

    public SqlConnector connect(String database) throws SQLException {
        return connectorFactory.connect(database);
    }

    public BranchForkResult fork(String fromBranch, String currentBranch) {
        validateBranch(fromBranch, "fromBranch");
        validateBranch(currentBranch, "currentBranch");

        if (!metadataStore.createBranch(currentBranch, fromBranch)) {
            throw fail("Branch already exists: " + currentBranch, null);
        }

        ensureSharedContainer();
        waitUntilReady();

        String branchPrefix = sanitizeBranchName(currentBranch);
        List<BranchDatabase> forkedDatabases = new ArrayList<>();
        forkedDatabases.add(forkDatabase(branchPrefix, POSTGRES_LOGICAL_NAME, config.adminDatabase(), currentBranch));

        List<BranchDatabase> trackedDatabases = metadataStore.databasesForBranch(fromBranch);
        List<BranchDatabase> forkedTrackedDatabases = new ArrayList<>();
        for (BranchDatabase parentDatabase : trackedDatabases) {
            BranchDatabase forked = forkDatabase(branchPrefix, parentDatabase.logicalName(), parentDatabase.databaseName(), currentBranch);
            forkedDatabases.add(forked);
            forkedTrackedDatabases.add(forked);
        }

        metadataStore.recordDatabases(currentBranch, forkedTrackedDatabases);
        out("Recorded branch '" + currentBranch + "' forked from '" + fromBranch + "' with " + forkedDatabases.size() + " database(s).");
        out("Branch fork completed for '" + currentBranch + "'.");
        return new BranchForkResult(fromBranch, currentBranch, config.containerName(),
                forkedDatabases.stream().map(BranchDatabase::databaseName).toList());
    }

    private BranchDatabase forkDatabase(String branchPrefix, String logicalName, String parentDatabaseName, String currentBranch) {
        String childDatabaseName = branchPrefix + "_" + logicalName;
        out("Forking database '" + parentDatabaseName + "' into '" + childDatabaseName + "' for branch '" + currentBranch + "'.");
        executeSql("fork database '" + logicalName + "'", config.adminDatabase(),
                "CREATE DATABASE \"" + childDatabaseName + "\" WITH TEMPLATE \"" + parentDatabaseName + "\"");
        return new BranchDatabase(logicalName, childDatabaseName);
    }

    private void ensureSharedContainer() {
        CommandResult inspect = run(List.of("docker", "inspect", "--format", "{{.State.Running}}", config.containerName()),
                "inspect shared PostgreSQL container", false);
        if (inspect.succeeded() && inspect.output().equals("true")) {
            out("Reusing shared PostgreSQL container '" + config.containerName() + "'.");
            return;
        }

        out("Starting shared PostgreSQL container '" + config.containerName() + "'.");
        runChecked("start shared PostgreSQL container", List.of(
                "docker", "run", "--detach", "--name", config.containerName(),
                "--publish", config.hostPort() + ":" + CONTAINER_PORT,
                "--env", "POSTGRES_USER=" + config.user(),
                "--env", "POSTGRES_PASSWORD=" + config.password(),
                "--env", "POSTGRES_DB=" + config.adminDatabase(),
                config.image()
        ));
    }

    private void waitUntilReady() {
        out("Waiting for shared PostgreSQL container '" + config.containerName() + "' to accept connections on port " + config.hostPort() + ".");
        for (int attempt = 1; attempt <= READY_ATTEMPTS; attempt++) {
            if (canConnect()) {
                out("Shared PostgreSQL container '" + config.containerName() + "' is ready.");
                return;
            }
            if (attempt < READY_ATTEMPTS) {
                sleepBeforeRetry();
            }
        }
        throw fail("Shared PostgreSQL container did not become ready after " + READY_ATTEMPTS + " attempts.", null);
    }

    private boolean canConnect() {
        try (SqlConnector connector = connectorFactory.connect(config.adminDatabase())) {
            return true;
        } catch (SQLException exception) {
            return false;
        }
    }

    private void executeSql(String operation, String database, String sql) {
        try (SqlConnector connector = connectorFactory.connect(database)) {
            connector.execute(sql);
        } catch (SQLException exception) {
            throw fail("Could not " + operation + ": " + exception.getMessage(), exception);
        }
    }

    private static PostgresConnectorFactory defaultConnectorFactory(PostgresDockerConfig config) {
        return database -> new PostgresConnector(jdbcUrl(config, database));
    }

    private static String jdbcUrl(PostgresDockerConfig config, String database) {
        return "jdbc:postgresql://localhost:" + config.hostPort() + "/" + database
                + "?user=" + config.user() + "&password=" + config.password();
    }

    private static BranchMetadataStore defaultMetadataStore() {
        MetadataServerConfig metadataConfig = MetadataServerConfig.getInstance();
        PostgresConnectorFactory factory = database -> new PostgresConnector(metadataJdbcUrl(metadataConfig, database));
        return new PostgresBranchMetadataStore(factory, metadataConfig.adminDatabase(), metadataConfig.database());
    }

    private static String metadataJdbcUrl(MetadataServerConfig metadataConfig, String database) {
        return "jdbc:postgresql://" + metadataConfig.host() + ":" + metadataConfig.port() + "/" + database
                + "?user=" + metadataConfig.user() + "&password=" + metadataConfig.password();
    }

    private void runChecked(String operation, List<String> command) {
        CommandResult result = run(command, operation, true);
        if (!result.succeeded()) {
            throw fail("Could not " + operation + ". Docker exited with code " + result.exitCode() + ".", null);
        }
    }

    private CommandResult run(List<String> command, String operation, boolean printFailure) {
        try {
            CommandResult result = commandRunner.run(command);
            if (printFailure && !result.succeeded()) {
                err("Failed to " + operation + " (exit " + result.exitCode() + "): " + result.output());
            }
            return result;
        } catch (IOException exception) {
            throw fail("Could not " + operation + ": " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw fail("Interrupted while attempting to " + operation + ".", exception);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw fail("Interrupted while waiting for PostgreSQL to start.", exception);
        }
    }

    private static void validateBranch(String branch, String argumentName) {
        if (branch == null || !BRANCH_NAME.matcher(branch).matches()) {
            throw new IllegalArgumentException(argumentName + " must be a non-empty Git-style branch name");
        }
    }

    private static String sanitizeBranchName(String branch) {
        String readableBranch = branch.replaceAll("[^a-zA-Z0-9_.-]", "_").toLowerCase();
        return readableBranch.length() > 40 ? readableBranch.substring(0, 40) : readableBranch;
    }

    private static BranchForkException fail(String message, Throwable cause) {
        err(message);
        return cause == null ? new BranchForkException(message) : new BranchForkException(message, cause);
    }

    private static void out(String message) {
        System.out.println("[BranchFork] " + message);
        System.out.flush();
    }

    private static void err(String message) {
        System.err.println("[BranchFork] " + message);
        System.err.flush();
    }
}
