package org.example.config;

/** Singleton configuration for the standalone PostgreSQL server that stores dbgit branch metadata. */
public final class MetadataStoreConfig {
    private static final MetadataStoreConfig INSTANCE = load();

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String adminDatabase;
    private final String database;
    private final String dialect;

    private MetadataStoreConfig(String host, int port, String user, String password, String adminDatabase,
                                 String database, String dialect) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.adminDatabase = adminDatabase;
        this.database = database;
        this.dialect = dialect;
    }

    public static MetadataStoreConfig getInstance() {
        return INSTANCE;
    }

    public String host() {
        return host;
    }

    public int port() {
        return port;
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

    public String database() {
        return database;
    }

    public String dialect() {
        return dialect;
    }

    public ConnectionSettings connectionTo(String database) {
        return new ConnectionSettings(host, port, user, password, database);
    }

    private static MetadataStoreConfig load() {
        return new MetadataStoreConfig(
                DbGitProperties.required("metadata.host"),
                Integer.parseInt(DbGitProperties.required("metadata.port")),
                DbGitProperties.required("metadata.user"),
                DbGitProperties.required("metadata.password"),
                DbGitProperties.required("metadata.admin-database"),
                DbGitProperties.required("metadata.database"),
                DbGitProperties.optional("metadata.dialect", "postgresql"));
    }
}
