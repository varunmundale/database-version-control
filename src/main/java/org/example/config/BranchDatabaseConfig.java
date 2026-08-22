package org.example.config;

/** Singleton configuration for the shared PostgreSQL Docker instance branch databases are forked into. */
public final class BranchDatabaseConfig {
    private static final BranchDatabaseConfig INSTANCE = load();

    private final String containerName;
    private final String image;
    private final String user;
    private final String password;
    private final String adminDatabase;
    private final int hostPort;
    private final String dialect;

    private BranchDatabaseConfig(String containerName, String image, String user, String password,
                                  String adminDatabase, int hostPort, String dialect) {
        this.containerName = containerName;
        this.image = image;
        this.user = user;
        this.password = password;
        this.adminDatabase = adminDatabase;
        this.hostPort = hostPort;
        this.dialect = dialect;
    }

    public static BranchDatabaseConfig getInstance() {
        return INSTANCE;
    }

    /**
     * Builds a config directly rather than reading {@code dbgit.json} - for a test that wants to assert against a
     * config of its own (e.g. {@code Forker}'s real shared-container docker commands) without depending on, or
     * being able to break, whatever the classpath's {@code dbgit.json} happens to say.
     */
    public static BranchDatabaseConfig of(String containerName, String image, String user, String password,
                                           String adminDatabase, int hostPort, String dialect) {
        return new BranchDatabaseConfig(containerName, image, user, password, adminDatabase, hostPort, dialect);
    }

    public String containerName() {
        return containerName;
    }

    public String image() {
        return image;
    }

    public String user() {
        return user;
    }

    public String password() {
        return password;
    }

    public String adminDatabase() {
        return adminDatabase;
    }

    public int hostPort() {
        return hostPort;
    }

    public String dialect() {
        return dialect;
    }

    public ConnectionSettings connectionTo(String database) {
        return new ConnectionSettings("localhost", hostPort, user, password, database);
    }

    private static BranchDatabaseConfig load() {
        var section = DbGitConfig.section("branchDatabases");
        return new BranchDatabaseConfig(
                DbGitConfig.requiredText(section, "containerName"),
                DbGitConfig.requiredText(section, "image"),
                DbGitConfig.requiredText(section, "user"),
                DbGitConfig.requiredText(section, "password"),
                DbGitConfig.requiredText(section, "adminDatabase"),
                DbGitConfig.requiredInt(section, "hostPort"),
                DbGitConfig.optionalText(section, "dialect", "postgresql"));
    }
}
