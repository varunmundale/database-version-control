package org.example.models.tracking;

import org.example.models.schema.StableId;

import java.util.Objects;

/**
 * The physical database a branch tracks, as the metadata store records it: a signature plus enough detail to say
 * <em>which</em> database that is. Deliberately carries no credentials - those live only in the workspace's own
 * {@code .dbgit}, never in the shared metadata store, so recording what main points at never distributes a secret.
 *
 * <p>The signature covers host, port and database name: the identity of the database itself, not of whoever
 * connects to it. Re-running {@code dbgit init} against the same target therefore produces the same signature and
 * changes nothing, which is what makes the command idempotent.
 */
public record TrackedDatabase(String branch, String signature, String host, int port, String database, String user) {
    public TrackedDatabase {
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(signature, "signature must not be null");
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(database, "database must not be null");
        Objects.requireNonNull(user, "user must not be null");
    }

    public static TrackedDatabase of(String branch, String host, int port, String database, String user) {
        return new TrackedDatabase(branch, signatureOf(host, port, database), host, port, database, user);
    }

    /** Deterministic, credential-free: the same database always signs the same, from any workspace. */
    public static String signatureOf(String host, int port, String database) {
        return StableId.of("connection", host + ":" + port + "/" + database).value();
    }

    /** How this database reads in command output, e.g. {@code "app_prod@db.internal:5432"}. */
    public String describe() {
        return database + "@" + host + ":" + port;
    }
}
