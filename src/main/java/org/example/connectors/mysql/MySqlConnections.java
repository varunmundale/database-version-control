package org.example.connectors.mysql;

import org.example.config.ConnectionSettings;
import org.example.connectors.JdbcConnections;

/**
 * The one definition of a MySQL JDBC URL.
 *
 * <p>Every connection asks the server to run in {@code ANSI_QUOTES} sql_mode from the moment it connects:
 * {@link org.example.repository.BranchDatabaseRepository} emits {@code CREATE DATABASE "name"} - a double-quoted
 * identifier - regardless of dialect, and MySQL only accepts double quotes as identifiers under that mode; without
 * it a double-quoted token is a string literal and the statement is invalid. Asking for the mode here, once, means
 * no call site has to know which dialect it is talking to.
 */
public final class MySqlConnections implements JdbcConnections {
    public static final MySqlConnections INSTANCE = new MySqlConnections();

    private MySqlConnections() {
    }

    @Override
    public String jdbcUrl(ConnectionSettings settings) {
        return "jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database()
                + "?sessionVariables=sql_mode='ANSI_QUOTES'";
    }
}
