package org.example.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.ConnectionSettings;
import org.example.protocol.RequestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One user's local {@code .dbgit} directory: a {@code HEAD} file naming the branch they are on, and a
 * {@code config.json} holding the connection their workspace dials. Which branches exist and what they contain
 * lives in the metadata store instead.
 *
 * <p>This is client-side on purpose. It used to sit in the daemon, which meant one shared {@code HEAD} for every
 * user of that daemon - A's {@code checkout} silently moved B. Now each workspace owns its own and tells the
 * daemon which branch it means, per request.
 *
 * <p>{@code config.json} is the only place a password is written. The metadata store records what a branch tracks
 * so any workspace can check it agrees, but never how to authenticate to it.
 */
public final class ClientWorkspace {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path directory;
    private final Path headFile;
    private final Path configFile;

    public ClientWorkspace(Path workingDirectory) {
        this.directory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null").resolve(".dbgit");
        this.headFile = directory.resolve("HEAD");
        this.configFile = directory.resolve("config.json");
    }

    /** What this workspace sends with every request: who is asking, where they are, and how to reach what main tracks. */
    public RequestContext requestContext() {
        return new RequestContext(System.getProperty("user.name"), currentBranch(),
                trackedConnection(RequestContext.DEFAULT_BRANCH).orElse(null));
    }

    /** The branch this workspace is on, defaulting to {@code main} in a directory dbgit hasn't been used in yet. */
    public String currentBranch() {
        initialize();
        return read().trim();
    }

    public void checkout(String branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        initialize();
        write(branch);
    }

    /** The connection this workspace uses for a branch, or empty if {@code dbgit init} has not configured one. */
    public Optional<ConnectionSettings> trackedConnection(String branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        JsonNode branches = readConfig().get("branches");
        JsonNode connection = branches == null ? null : branches.get(branch);
        if (connection == null) {
            return Optional.empty();
        }
        return Optional.of(new ConnectionSettings(
                connection.get("host").asText(),
                connection.get("port").asInt(),
                connection.get("user").asText(),
                connection.get("password").asText(""),
                connection.get("database").asText()));
    }

    /** Points a branch at a connection, replacing any previous one. Writes the password to local disk, nowhere else. */
    public void track(String branch, ConnectionSettings settings) {
        Objects.requireNonNull(branch, "branch must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        initialize();

        ObjectNode root = readConfig();
        ObjectNode branches = root.has("branches") && root.get("branches").isObject()
                ? (ObjectNode) root.get("branches")
                : root.putObject("branches");
        ObjectNode connection = branches.putObject(branch);
        connection.put("host", settings.host());
        connection.put("port", settings.port());
        connection.put("database", settings.database());
        connection.put("user", settings.user());
        connection.put("password", settings.password());
        writeConfig(root);
    }

    private ObjectNode readConfig() {
        if (!Files.exists(configFile)) {
            return JSON.createObjectNode();
        }
        try {
            JsonNode root = JSON.readTree(configFile.toFile());
            return root.isObject() ? (ObjectNode) root : JSON.createObjectNode();
        } catch (IOException exception) {
            throw new WorkspaceException("Could not read '" + configFile + "': " + exception.getMessage(), exception);
        }
    }

    private void writeConfig(ObjectNode root) {
        try {
            Files.writeString(configFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + System.lineSeparator());
        } catch (IOException exception) {
            throw new WorkspaceException("Could not update '" + configFile + "': " + exception.getMessage(), exception);
        }
    }

    /** Creates {@code .dbgit/HEAD} pointing at the default branch, unless this directory already has one. */
    private void initialize() {
        if (Files.exists(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new WorkspaceException("Could not create '" + directory + "': " + exception.getMessage(), exception);
        }
        write(RequestContext.DEFAULT_BRANCH);
    }

    private String read() {
        try {
            return Files.readString(headFile);
        } catch (IOException exception) {
            throw new WorkspaceException("Could not read '" + headFile + "': " + exception.getMessage(), exception);
        }
    }

    private void write(String branch) {
        try {
            Files.writeString(headFile, branch + System.lineSeparator());
        } catch (IOException exception) {
            throw new WorkspaceException("Could not update '" + headFile + "': " + exception.getMessage(), exception);
        }
    }
}
