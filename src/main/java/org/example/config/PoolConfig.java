package org.example.config;

/**
 * How dbgit's two kinds of database connection are pooled: one fixed pool for the metadata store, and a bounded
 * set of small pools for the branch and tracked databases, which are many and come and go.
 *
 * <p>{@code metadata.maxSize} defaults to <em>twice</em> {@link ConcurrencyConfig#handlerThreads()} and may not be
 * configured below it. Every mutating command holds two metadata connections at once - one pinned for the whole
 * command by its branch lock, one for the work itself - so a smaller pool lets threads that already hold branch
 * locks queue behind threads waiting for those same locks, which is a deadlock rather than a slowdown. Deriving
 * the default means the only way to reach that state is to ask for it explicitly, and asking is refused here.
 */
public final class PoolConfig {
    private static final PoolConfig INSTANCE = load();

    private static final int CONNECTIONS_PER_COMMAND = 2;
    private static final int DEFAULT_METADATA_CONNECTION_TIMEOUT_MS = 5_000;
    private static final int DEFAULT_BRANCH_MAX_SIZE = 4;
    private static final int DEFAULT_BRANCH_IDLE_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_BRANCH_MAX_POOLS = 32;

    /** The metadata store's pool: one target, fixed size, sized against the daemon's thread count. */
    public record MetadataPool(int maxSize, int connectionTimeoutMs) {
    }

    /**
     * The branch databases' pools: one per target, created on demand and closed on eviction. {@code maxPools}
     * bounds how many distinct databases are held open at once, because branches are unbounded and each pool
     * costs real connections on the shared container.
     */
    public record BranchPool(int maxSize, int idleTimeoutMs, int maxPools) {
    }

    private final MetadataPool metadata;
    private final BranchPool branch;

    /** Public so an embedder - or a test - can build one without writing a {@code dbgit.json}. Validates as it goes. */
    public PoolConfig(MetadataPool metadata, BranchPool branch, int handlerThreads) {
        int required = CONNECTIONS_PER_COMMAND * handlerThreads;
        if (metadata.maxSize() < required) {
            throw new IllegalStateException("pools.metadata.maxSize (" + metadata.maxSize() + ") must be at least "
                    + CONNECTIONS_PER_COMMAND + " x concurrency.handlerThreads (" + handlerThreads + ") = " + required
                    + ": each in-flight command holds a lock connection and a work connection, so a smaller pool"
                    + " can deadlock threads that already hold branch locks.");
        }
        this.metadata = metadata;
        this.branch = requirePositive(branch);
    }

    public static PoolConfig getInstance() {
        return INSTANCE;
    }

    public MetadataPool metadata() {
        return metadata;
    }

    public BranchPool branch() {
        return branch;
    }

    private static BranchPool requirePositive(BranchPool branch) {
        if (branch.maxSize() < 1) {
            throw new IllegalStateException("pools.branch.maxSize must be at least 1, but was " + branch.maxSize() + ".");
        }
        if (branch.maxPools() < 1) {
            throw new IllegalStateException("pools.branch.maxPools must be at least 1, but was " + branch.maxPools() + ".");
        }
        if (branch.idleTimeoutMs() < 1) {
            throw new IllegalStateException("pools.branch.idleTimeoutMs must be at least 1, but was " + branch.idleTimeoutMs() + ".");
        }
        return branch;
    }

    private static PoolConfig load() {
        var section = DbGitConfig.optionalSection("pools");
        var metadataSection = section.path("metadata");
        var branchSection = section.path("branch");
        int handlerThreads = ConcurrencyConfig.getInstance().handlerThreads();

        return new PoolConfig(
                new MetadataPool(
                        DbGitConfig.optionalInt(metadataSection, "maxSize", CONNECTIONS_PER_COMMAND * handlerThreads),
                        DbGitConfig.optionalInt(metadataSection, "connectionTimeoutMs", DEFAULT_METADATA_CONNECTION_TIMEOUT_MS)),
                new BranchPool(
                        DbGitConfig.optionalInt(branchSection, "maxSize", DEFAULT_BRANCH_MAX_SIZE),
                        DbGitConfig.optionalInt(branchSection, "idleTimeoutMs", DEFAULT_BRANCH_IDLE_TIMEOUT_MS),
                        DbGitConfig.optionalInt(branchSection, "maxPools", DEFAULT_BRANCH_MAX_POOLS)),
                handlerThreads);
    }
}
