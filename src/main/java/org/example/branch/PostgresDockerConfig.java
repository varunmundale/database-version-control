package org.example.branch;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Singleton configuration for the shared PostgreSQL Docker instance. */
public final class PostgresDockerConfig {
    private static final PostgresDockerConfig INSTANCE = load();

    private final String containerName;
    private final String image;
    private final String user;
    private final String password;
    private final String adminDatabase;

    private PostgresDockerConfig(String containerName, String image, String user, String password, String adminDatabase) {
        this.containerName = containerName;
        this.image = image;
        this.user = user;
        this.password = password;
        this.adminDatabase = adminDatabase;
    }

    public static PostgresDockerConfig getInstance() {
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

    private static PostgresDockerConfig load() {
        Properties properties = new Properties();
        try (InputStream input = PostgresDockerConfig.class.getClassLoader().getResourceAsStream("dbgit.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing dbgit.properties on the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read dbgit.properties", exception);
        }
        return new PostgresDockerConfig(
                required(properties, "docker.container-name"),
                required(properties, "docker.image"),
                required(properties, "postgres.user"),
                required(properties, "postgres.password"),
                required(properties, "postgres.admin-database")
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
