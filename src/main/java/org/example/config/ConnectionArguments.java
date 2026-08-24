package org.example.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses the connection flags {@code dbgit init} takes into a {@link ConnectionSettings}. Runs client-side, so a
 * typo is reported without a round trip to the daemon.
 */
public final class ConnectionArguments {
    private ConnectionArguments() {
    }

    /**
     * Pulls the required {@code --author <name>} out of an init argument list, so the rest can be parsed as a
     * connection by {@link #parse}.
     *
     * @param arguments mutated in place: the flag and its value are removed once found
     * @return the author name
     * @throws IllegalArgumentException if {@code --author} is missing
     */
    public static String extractAuthor(List<String> arguments) {
        for (int index = 0; index < arguments.size(); index++) {
            if (arguments.get(index).equals("--author")) {
                String author = value(arguments, "--author", index + 1);
                arguments.remove(index + 1);
                arguments.remove(index);
                return author;
            }
        }
        throw new IllegalArgumentException("--author is required. " + usage());
    }

    public static ConnectionSettings parse(List<String> arguments) {
        String host = null;
        String database = null;
        String user = null;
        String password = null;
        Integer port = null;

        List<String> unknown = new ArrayList<>();
        for (int index = 0; index < arguments.size(); index++) {
            String flag = arguments.get(index);
            switch (flag) {
                case "--host" -> host = value(arguments, flag, ++index);
                case "--port" -> port = parsePort(value(arguments, flag, ++index));
                case "--database" -> database = value(arguments, flag, ++index);
                case "--user" -> user = value(arguments, flag, ++index);
                case "--password" -> password = value(arguments, flag, ++index);
                default -> unknown.add(flag);
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown option(s) " + unknown + ". " + usage());
        }
        if (host == null || database == null || user == null || port == null || password == null) {
            throw new IllegalArgumentException(
                    "--host, --port, --database, --user and --password are all required. " + usage());
        }
        return new ConnectionSettings(host, port, user, password, database);
    }

    public static String usage() {
        return "Usage: dbgit init --host <host> --port <port> --database <database> --user <user> "
                + "--password <password> --author <name>";
    }

    private static int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--port must be a number. " + usage());
        }
    }

    private static String value(List<String> arguments, String flag, int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(flag + " needs a value. " + usage());
        }
        return arguments.get(index);
    }
}
