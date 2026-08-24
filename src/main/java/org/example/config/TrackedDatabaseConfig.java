package org.example.config;

import org.example.models.schema.StableId;

import java.util.Objects;

/**
 * The physical database a branch tracks, as the metadata store records it - the credential-free half of a
 * {@link ConnectionSettings}, so recording what {@code main} tracks never distributes a secret. The password stays
 * in the workspace's own {@code .dbgit}. The signature is derived from host/port/database rather than stored, which
 * is what makes {@code dbgit init} idempotent: the same target always signs the same.
 */
public record TrackedDatabaseConfig(String branch, String host, int port, String database, String user) {
    public TrackedDatabaseConfig {
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(user, "user must not be null");
    }

    /** Drops the password: everything a {@link ConnectionSettings} says except how to authenticate. */
    public static TrackedDatabaseConfig of(String branch, ConnectionSettings settings) {
        Objects.requireNonNull(settings, "settings must not be null");
        return new TrackedDatabaseConfig(branch, settings.host(), settings.port(), settings.database(), settings.user());
    }

    public String signature() {
        return StableId.of("connection", host + ":" + port + "/" + database).value();
    }

    /** True when both point at the same physical database, whoever connects to it. */
    public boolean tracksSameDatabaseAs(TrackedDatabaseConfig other) {
        return signature().equals(other.signature());
    }

    /** How this database reads in command output, e.g. {@code "app_prod@db.internal:5432"}. */
    public String describe() {
        return database + "@" + host + ":" + port;
    }
}
