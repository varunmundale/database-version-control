package org.example.server;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Configuration shared by {@link DbServiceMain} and {@link DbGitClient} so they agree on the service port. */
public final class ServiceConfig {
    private static final ServiceConfig INSTANCE = load();

    private final int port;

    private ServiceConfig(int port) {
        this.port = port;
    }

    public static ServiceConfig getInstance() {
        return INSTANCE;
    }

    public int port() {
        return port;
    }

    private static ServiceConfig load() {
        Properties properties = new Properties();
        try (InputStream input = ServiceConfig.class.getClassLoader().getResourceAsStream("dbgit.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing dbgit.properties on the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read dbgit.properties", exception);
        }
        return new ServiceConfig(Integer.parseInt(required(properties, "service.port")));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required configuration key: " + key);
        }
        return value;
    }
}
