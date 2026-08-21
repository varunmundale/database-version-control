package org.example.connectors.h2;

import org.example.config.ConnectionSettings;
import org.example.connectors.JdbcConnections;

/** The one definition of an H2 JDBC URL. Every H2 database dbgit opens is in-memory and lives as long as the JVM. */
public final class H2Connections implements JdbcConnections {
    public static final H2Connections INSTANCE = new H2Connections();

    private H2Connections() {
    }

    @Override
    public String jdbcUrl(ConnectionSettings settings) {
        return inMemoryUrl(settings.database());
    }

    public static String inMemoryUrl(String databaseName) {
        return "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1";
    }
}
