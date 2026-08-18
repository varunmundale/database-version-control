package org.example.branch;

import java.util.Objects;

/** Local PostgreSQL container settings; the password is never included in service logs. */
public record PostgresDockerConfig(String image, String user, String password, String database) {
    public PostgresDockerConfig {
        requireValue(image, "image");
        requireValue(user, "user");
        requireValue(password, "password");
        requireValue(database, "database");
    }

    public static PostgresDockerConfig localDefault() {
        return new PostgresDockerConfig("postgres:16-alpine", "postgres", "postgres", "branch_db");
    }

    private static void requireValue(String value, String name) {
        if (Objects.requireNonNull(value, name + " must not be null").isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
