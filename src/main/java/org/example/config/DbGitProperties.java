package org.example.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Loads {@code dbgit.properties} once from the classpath; shared by every typed config in this package. */
final class DbGitProperties {
    private static final Properties INSTANCE = load();

    private DbGitProperties() {
    }

    static String required(String key) {
        String value = INSTANCE.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required configuration key: " + key);
        }
        return value;
    }

    static String optional(String key, String defaultValue) {
        String value = INSTANCE.getProperty(key);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream input = DbGitProperties.class.getClassLoader().getResourceAsStream("dbgit.properties")) {
            if (input == null) {
                throw new IllegalStateException("Missing dbgit.properties on the classpath");
            }
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read dbgit.properties", exception);
        }
        return properties;
    }
}
