package org.example.request;

import org.example.config.ConnectionSettings;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The one line of {@code key=value} pairs a client sends before its command, and the only place the wire format of
 * a {@link RequestContext} is written or read.
 *
 * <pre>
 * DBGIT/1 author=varun branch=feature/orders db-host=localhost db-port=5432 db-database=app db-user=varun db-password=s3cret
 * </pre>
 *
 * <p>Values are percent-encoded, because a password may contain a space or an {@code =} and this line is split on
 * both. The version prefix is mandatory: with the branch <em>and</em> the credentials now coming from the client,
 * a request without a header carries too little to act on, so guessing at defaults would only fail later and less
 * clearly.
 */
public final class RequestHeader {
    public static final String VERSION = "DBGIT/1";
    private static final String PREFIX = VERSION + " ";

    private RequestHeader() {
    }

    public static boolean isHeader(String line) {
        return line != null && (line.equals(VERSION) || line.startsWith(PREFIX));
    }

    public static String render(RequestContext request) {
        StringBuilder header = new StringBuilder(PREFIX);
        append(header, "author", request.author());
        append(header, "branch", request.branch());
        request.trackedDatabaseIfConfigured().ifPresent(tracked -> {
            append(header, "db-host", tracked.host());
            append(header, "db-port", String.valueOf(tracked.port()));
            append(header, "db-database", tracked.database());
            append(header, "db-user", tracked.user());
            append(header, "db-password", tracked.password());
        });
        return header.toString().stripTrailing();
    }

    /** @throws IllegalArgumentException if the line is not a {@code DBGIT/1} header */
    public static RequestContext parse(String line) {
        if (!isHeader(line)) {
            throw new IllegalArgumentException(
                    "Unsupported protocol; upgrade your dbgit client. Expected a line starting with " + VERSION + ".");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String pair : line.substring(VERSION.length()).trim().split("\\s+")) {
            if (pair.isEmpty()) {
                continue;
            }
            int separator = pair.indexOf('=');
            if (separator < 0) {
                throw new IllegalArgumentException("Malformed header field '" + pair + "'; expected key=value.");
            }
            fields.put(pair.substring(0, separator), decode(pair.substring(separator + 1)));
        }
        return new RequestContext(fields.get("author"), fields.get("branch"), trackedDatabase(fields));
    }

    private static ConnectionSettings trackedDatabase(Map<String, String> fields) {
        if (!fields.containsKey("db-host")) {
            return null;
        }
        return new ConnectionSettings(fields.get("db-host"),
                Integer.parseInt(fields.getOrDefault("db-port", "5432")),
                fields.getOrDefault("db-user", ""),
                fields.getOrDefault("db-password", ""),
                fields.getOrDefault("db-database", ""));
    }

    private static void append(StringBuilder header, String key, String value) {
        header.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8)).append(' ');
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
