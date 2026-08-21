package org.example.connectors.postgres;

import org.example.config.ConnectionSettings;
import org.example.connectors.JdbcConnections;

/** The one definition of a PostgreSQL JDBC URL, shared by branch databases and the metadata store. */
public final class PostgresConnections implements JdbcConnections {
    public static final PostgresConnections INSTANCE = new PostgresConnections();

    private PostgresConnections() {
    }

    @Override
    public String jdbcUrl(ConnectionSettings settings) {
        return "jdbc:postgresql://" + settings.host() + ":" + settings.port() + "/" + settings.database();
    }
}
