package org.example.config;

import java.util.Objects;

/** Everything a {@link org.example.connectors.ConnectorFactory} needs to open one JDBC connection. */
public record ConnectionSettings(String host, int port, String user, String password, String database) {
    public ConnectionSettings {
        Objects.requireNonNull(host, "host must not be null");
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(password, "password must not be null");
        Objects.requireNonNull(database, "database must not be null");
    }

    public ConnectionSettings withDatabase(String otherDatabase) {
        return new ConnectionSettings(host, port, user, password, otherDatabase);
    }
}
