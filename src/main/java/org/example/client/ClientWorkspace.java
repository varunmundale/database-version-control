package org.example.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.ConnectionSettings;
import org.example.request.RequestContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * One user's local {@code .dbgit} directory: a {@code HEAD} file naming the branch they are on, and a
 * {@code config.json} holding the connection and author identity their workspace dials the daemon with per
 * request. Deliberately client-side, not daemon-side, so one user's {@code checkout} can't move another's HEAD.
 * {@code config.json} is the only place a password is written.
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

    /** What this workspace sends with every request: its configured author, where it is, and how to reach what main tracks. */
    public RequestContext requestContext() {
        return new RequestContext(author().orElse(null), currentBranch(),
                trackedConnection(RequestContext.DEFAULT_BRANCH).orElse(null));
    }

    /** This workspace's configured author identity, set via {@code dbgit init --author}, if any. */
    public Optional<String> author() {
        JsonNode author = readConfig().get("author");
        return author == null || author.isNull() ? Optional.empty() : Optional.of(author.asText());
    }

    /** Sets this workspace's author identity, used to attribute commits from here on. */
    public void trackAuthor(String author) {
        Objects.requireNonNull(author, "author must not be null");
        initialize();
        ObjectNode root = readConfig();
        root.put("author", author);
        writeConfig(root);
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
