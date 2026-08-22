package org.example.integration.support;

import org.example.config.BranchDatabaseConfig;
import org.example.config.ConcurrencyConfig;
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
 * A whole dbgit installation, assembled per test: a live daemon on a port of its own, the real metadata store on a
 * PostgreSQL container, real branch databases on in-memory H2, and a client with its own {@code .dbgit} directory
 * talking to the daemon over a socket.
 *
 * <p>What separates these tests from the unit tests next door is that almost nothing is a double. The versioning
 * service is the real {@link MetadataVersioningService} over real jOOQ against real PostgreSQL; branches really are
 * serialized through PostgreSQL advisory locks; DDL really runs against a database engine, and a fork really
 * replays a history into a new one. Only two things are stood in for, and for reasons that are not about the code
 * under test: the branch databases are H2 rather than one PostgreSQL container per branch
 * (see {@link H2Databases}), and the {@code docker} CLI is answered by a stub, since with H2 there is no container
 * to bring up.
 *
 * <p>Each test gets an empty metadata store and empty branch databases, so commit and changeset numbering starts
 * at 1 every time and a test can say {@code commit #1} rather than counting what ran before it.
 */
public abstract class DbGitIntegrationTest {
    /** The database {@code main} tracks - a real, pre-existing one in production; an H2 database here. */
    protected static final String TRACKED_DATABASE = "app";

    /** Shared for the JVM's lifetime, because the singletons the daemon reaches them through can only be built once. */
    private static final H2Databases DATABASES = new H2Databases();

    protected final DatabaseSchema schema = new DatabaseSchema(DATABASES);

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
        DATABASES.reset();

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
        return dbgit("init", "--host", "localhost", "--port", "5432",
                "--database", TRACKED_DATABASE, "--user", "postgres", "--password", "postgres");
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
     * Another caller of the same daemon, with a {@code .dbgit} directory - and so a checked-out branch - of its
     * own. What a concurrency test uses in place of the shell scripts' {@code new_workspace}/{@code dbgit_in}: the
     * daemon holds no per-user state, so two workspaces talking to the one running daemon are genuinely
     * independent callers, not a simulation of them.
     */
    protected DbGitCli newWorkspace(String name) throws IOException {
        Path directory = Files.createDirectory(workspaceDirectory.resolve(name));
        return cli.otherWorkspace(directory, daemon.port());
    }

    private void serve() {
        try {
            daemon.serve();
        } catch (IOException ignored) {
            // The listener was closed while accepting; that is how a test ends.
        }
    }

    private static Forker forker() {
        return new Forker(new NoDocker(),
                new BranchDatabaseRepository(BranchDatabaseConfig.getInstance(), DATABASES),
                new MetadataVersioningService());
    }

    /**
     * The real advisory lock, on the metadata container. Every mutating command takes it for its whole duration,
     * so running it here rather than the in-memory double keeps the tests honest about a branch lock being a
     * PostgreSQL session lock that has to be acquired, held across side effects and released.
     */
    private static BranchLocks locks() {
        return new BranchLocks(new AdvisoryBranchLock(),
                Duration.ofMillis(ConcurrencyConfig.getInstance().lockTimeoutMs()));
    }

    /**
     * Answers {@code docker inspect} as though the scratchpad container were already up. With branch databases on
     * H2 there is nothing to start, and the readiness poll that follows connects for real - to H2 - and passes.
     */
    private static final class NoDocker implements CommandRunner {
        @Override
        public CommandResult run(List<String> command) {
            return new CommandResult(0, "true");
        }
    }
}
