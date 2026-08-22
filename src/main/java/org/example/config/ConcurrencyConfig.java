package org.example.config;

/**
 * How much work the {@code dbService} daemon does at once, and how long it is willing to wait for anything.
 *
 * <p>The whole section is optional, so a {@code dbgit.json} written before concurrency existed still boots on
 * these defaults. {@code handlerThreads} should be sized for the <em>slowest</em> command rather than the average
 * one: a cold {@code checkout -b} pulls a Docker image and polls for readiness, and it occupies its thread for the
 * whole of that.
 */
public final class ConcurrencyConfig {
    private static final ConcurrencyConfig INSTANCE = load();

    private static final int DEFAULT_HANDLER_THREADS = 8;
    private static final int DEFAULT_QUEUE_DEPTH = 64;
    private static final int DEFAULT_SOCKET_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_DRAIN_TIMEOUT_MS = 30_000;
    private static final int DEFAULT_LOCK_TIMEOUT_MS = 60_000;

    private final int handlerThreads;
    private final int queueDepth;
    private final int socketTimeoutMs;
    private final int drainTimeoutMs;
    private final int lockTimeoutMs;

    /** Public so an embedder - or a test - can build one without writing a {@code dbgit.json}. Validates as it goes. */
    public ConcurrencyConfig(int handlerThreads, int queueDepth, int socketTimeoutMs, int drainTimeoutMs, int lockTimeoutMs) {
        this.handlerThreads = atLeast(handlerThreads, 1, "concurrency.handlerThreads");
        this.queueDepth = atLeast(queueDepth, 1, "concurrency.queueDepth");
        this.socketTimeoutMs = atLeast(socketTimeoutMs, 1, "concurrency.socketTimeoutMs");
        this.drainTimeoutMs = atLeast(drainTimeoutMs, 1, "concurrency.drainTimeoutMs");
        this.lockTimeoutMs = atLeast(lockTimeoutMs, 1, "concurrency.lockTimeoutMs");
    }

    public static ConcurrencyConfig getInstance() {
        return INSTANCE;
    }

    /** Commands run concurrently; each one occupies its thread for its whole duration, side effects included. */
    public int handlerThreads() {
        return handlerThreads;
    }

    /** Connections accepted while every thread is busy. Beyond this the daemon answers {@code ERR server busy}. */
    public int queueDepth() {
        return queueDepth;
    }

    public int socketTimeoutMs() {
        return socketTimeoutMs;
    }

    /** How long {@code close()} lets in-flight commands finish - a reset mid-{@code DROP DATABASE} should not be killed. */
    public int drainTimeoutMs() {
        return drainTimeoutMs;
    }

    /**
     * How long a command waits for a branch lock before giving up. Generous by default: a lock is held across real
     * DDL and a database rebuild, so a short timeout would report a healthy branch as busy.
     */
    public int lockTimeoutMs() {
        return lockTimeoutMs;
    }

    private static int atLeast(int value, int minimum, String key) {
        if (value < minimum) {
            throw new IllegalStateException(key + " must be at least " + minimum + ", but was " + value + ".");
        }
        return value;
    }

    private static ConcurrencyConfig load() {
        var section = DbGitConfig.optionalSection("concurrency");
        return new ConcurrencyConfig(
                DbGitConfig.optionalInt(section, "handlerThreads", DEFAULT_HANDLER_THREADS),
                DbGitConfig.optionalInt(section, "queueDepth", DEFAULT_QUEUE_DEPTH),
                DbGitConfig.optionalInt(section, "socketTimeoutMs", DEFAULT_SOCKET_TIMEOUT_MS),
                DbGitConfig.optionalInt(section, "drainTimeoutMs", DEFAULT_DRAIN_TIMEOUT_MS),
                DbGitConfig.optionalInt(section, "lockTimeoutMs", DEFAULT_LOCK_TIMEOUT_MS));
    }
}
