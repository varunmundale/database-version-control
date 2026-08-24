package org.example.integration.support;

import org.example.config.BranchDatabaseConfig;
import org.example.config.ConcurrencyConfig;
import org.example.connectors.spi.ConnectorRegistry;
import org.example.core.forker.BranchConnections;
import org.example.core.forker.Forker;
import org.example.core.forker.docker.CommandResult;
import org.example.core.forker.docker.CommandRunner;
import org.example.core.locking.AdvisoryBranchLock;
import org.example.core.locking.BranchLocks;
import org.example.core.versioning.MetadataVersioningService;
import org.example.repository.BranchDatabaseRepository;
import org.example.service.DbGitCommandListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A whole dbgit installation, assembled per test: a live daemon, the real metadata store on a PostgreSQL
 * container, branch databases on in-memory H2 (standing in only for the per-branch PostgreSQL container, not
 * for the code under test - see {@code branchDatabases.dialect: "h2"}), and a client with its own {@code .dbgit}
 * directory talking to the daemon over a socket. Each test gets an empty metadata store and empty branch
 * databases, so commit/changeset numbering starts at 1 every time.
 */
public abstract class DbGitIntegrationTest {
    /** The database {@code main} tracks - a real, pre-existing one in production; an H2 database here. */
    protected static final String TRACKED_DATABASE = "app";

    /** Shared for the JVM's lifetime, because the singletons the daemon reaches them through can only be built once. */
    private static final BranchDatabaseRepository BRANCH_DATABASES =
            new BranchDatabaseRepository(BranchDatabaseConfig.getInstance(), ConnectorRegistry.builtins().get("h2"));

    protected final DatabaseSchema schema = new DatabaseSchema();

    protected DbGitCli cli;

    @TempDir
    private Path workspaceDirectory;

    private DbGitCommandListener daemon;

    @BeforeAll
    static void startMetadataStore() {
        assumeTrue(MetadataStore.isDockerAvailable(),
                "Docker is required to run dbgit's metadata store; skipping the integration tests.");
        MetadataStore.start();
    }

    @BeforeEach
    void startDaemon() throws IOException {
        MetadataStore.reset();
        // main's tracked database is never created/dropped by a fork or reset like every other branch's is, since
        // dbgit add on main writes to it directly - so it must be emptied explicitly between tests.
        BRANCH_DATABASES.dropDatabase(TRACKED_DATABASE);

        daemon = new DbGitCommandListener(forker(), 0, ConcurrencyConfig.getInstance(), locks());
        Thread accepting = new Thread(this::serve, "dbgit-integration-daemon");
        accepting.setDaemon(true);
        accepting.start();

        cli = new DbGitCli(workspaceDirectory, daemon.port());
    }

    @AfterEach
    void stopDaemon() throws IOException {
        daemon.close();
    }

    /** Points {@code main} at its tracked database, the way every demo script opens. */
    protected CommandOutput initialiseMain() {
        return dbgit(initArguments("integration-test"));
    }

    /** The connection every workspace in this test points {@code main} at; only {@code --author} varies per caller. */
    private static String[] initArguments(String author) {
        return new String[] {"init", "--host", "localhost", "--port", "5432",
                "--database", TRACKED_DATABASE, "--user", "postgres", "--password", "postgres",
                "--author", author};
    }

    /** Runs a {@code dbgit} command and insists it succeeded, reporting whatever it said if it did not. */
    protected CommandOutput dbgit(String... arguments) {
        CommandOutput output = cli.run(arguments);
        assertTrue(output.succeeded(), () -> "dbgit " + String.join(" ", arguments) + " failed: " + output.text());
        return output;
    }

    /** {@code dbgit add}, insisting the statement was accepted and applied. */
    protected CommandOutput add(String ddl) {
        CommandOutput output = cli.add(ddl);
        assertTrue(output.succeeded(), () -> "dbgit add failed: " + output.text() + "\n  for: " + ddl);
        return output;
    }

    /** The database a forked branch's DDL actually lands in - what a test looks at to check the fork was real. */
    protected static String databaseOf(String branch) {
        return BranchConnections.forkedDatabaseName(branch);
    }

    /**
     * Another caller of the same daemon, with its own {@code .dbgit} directory, branch and author - the daemon
     * holds no per-user state, so this is a genuinely independent caller, not a simulation of one.
     */
    protected DbGitCli newWorkspace(String name) throws IOException {
        Path directory = Files.createDirectory(workspaceDirectory.resolve(name));
        DbGitCli workspace = cli.otherWorkspace(directory, daemon.port());
        CommandOutput init = workspace.run(initArguments(name));
        assertTrue(init.succeeded(), () -> "dbgit init for workspace '" + name + "' failed: " + init.text());
        return workspace;
    }

    private void serve() {
        try {
            daemon.serve();
        } catch (IOException ignored) {
            // The listener was closed while accepting; that is how a test ends.
        }
    }

    private static Forker forker() {
        return new Forker(new NoDocker(), BRANCH_DATABASES, new MetadataVersioningService());
    }

    /** The real advisory lock on the metadata container, not the in-memory double, so locking is genuinely tested. */
    private static BranchLocks locks() {
        return new BranchLocks(new AdvisoryBranchLock(),
                Duration.ofMillis(ConcurrencyConfig.getInstance().lockTimeoutMs()));
    }

    /** Answers {@code docker inspect} as though the scratchpad container were already up; H2 needs no real one. */
    private static final class NoDocker implements CommandRunner {
        @Override
        public CommandResult run(List<String> command) {
            return new CommandResult(0, "true");
        }
    }
}
