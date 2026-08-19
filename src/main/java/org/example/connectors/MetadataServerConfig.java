package org.example.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Singleton configuration for the standalone PostgreSQL server that stores dbgit branch metadata. */
public final class MetadataServerConfig {
    private static final MetadataServerConfig INSTANCE = load();

    private final String host;
    private final int port;
    private final String user;
    private final String password;
    private final String adminDatabase;
    private final String database;

    private MetadataServerConfig(String host, int port, String user, String password, String adminDatabase, String database) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.adminDatabase = adminDatabase;
        this.database = database;
    }

    public static MetadataServerConfig getInstance() {
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

    private static MetadataServerConfig load() {
        Properties properties = new Properties();
        try (InputStream input = MetadataServerConfig.class.getClassLoader().getResourceAsStream("dbgit.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing dbgit.properties on the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read dbgit.properties", exception);
        }
        return new MetadataServerConfig(
                required(properties, "metadata.host"),
                Integer.parseInt(required(properties, "metadata.port")),
                required(properties, "metadata.user"),
                required(properties, "metadata.password"),
                required(properties, "metadata.admin-database"),
                required(properties, "metadata.database")
        );
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required configuration key: " + key);
        }
        return value;
    }
}
