package org.example.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the connection flags {@code dbgit init} takes into a {@link ConnectionSettings}.
 *
 * <p>This runs in the <em>client</em>, not the daemon: the connection - password included - travels in the request
 * header now, so the client is what must understand these flags, and a typo is reported without a round trip. The
 * daemon only ever sees the parsed result.
 */
public final class ConnectionArguments {
    private static final int DEFAULT_PORT = 5432;

    private ConnectionArguments() {
    }

    public static ConnectionSettings parse(List<String> arguments) {
        String host = null;
        String database = null;
        String user = null;
        String password = "";
        int port = DEFAULT_PORT;

        List<String> unknown = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String flag = arguments.get(index);
            switch (flag) {
                case "--host" -> host = value(arguments, flag, ++index);
                case "--port" -> port = Integer.parseInt(value(arguments, flag, ++index));
                case "--database" -> database = value(arguments, flag, ++index);
                case "--user" -> user = value(arguments, flag, ++index);
                case "--password" -> password = value(arguments, flag, ++index);
                default -> unknown.add(flag);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s) " + unknown + ". " + usage());
        }
        if (host == null || database == null || user == null) {
            throw new IllegalArgumentException("--host, --database and --user are all required. " + usage());
        }
        return new ConnectionSettings(host, port, user, password, database);
    }

    public static String usage() {
        return "Usage: dbgit init --host <host> [--port " + DEFAULT_PORT
                + "] --database <database> --user <user> [--password <password>]";
    }

    private static String value(List<String> arguments, String flag, int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(flag + " needs a value. " + usage());
        }
        return arguments.get(index);
    }
}
