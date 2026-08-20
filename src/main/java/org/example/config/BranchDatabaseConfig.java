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
        return new BranchDatabaseConfig(
                DbGitProperties.required("docker.container-name"),
                DbGitProperties.required("docker.image"),
                DbGitProperties.required("postgres.user"),
                DbGitProperties.required("postgres.password"),
                DbGitProperties.required("postgres.admin-database"),
                Integer.parseInt(DbGitProperties.required("postgres.host-port")),
                DbGitProperties.optional("postgres.dialect", "postgresql"));
    }
}
