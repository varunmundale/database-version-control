package org.example.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.config.ConnectionSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The local half of dbgit's state, under {@code .dbgit}: a {@code HEAD} file naming the checked-out branch, and a
 * {@code config.json} holding the connections this workspace dials. Which branches exist and what they contain
 * lives in the metadata store instead.
 *
 * <p>{@code config.json} is the <em>only</em> place a password is written. The metadata store records what a branch
 * tracks so any workspace can check it agrees, but never how to authenticate to it - so `.dbgit`, which is local
 * and gitignored, is what holds the credential.
 *
 * <p>Unlike its siblings in this package it is not a singleton: it is scoped to one working directory, and a
 * process can serve more than one.
 */
public final class DbGitLocalRepository {
    private static final String DEFAULT_BRANCH = "main";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Path directory;
    private final Path headFile;
    private final Path configFile;

    public DbGitLocalRepository(Path workingDirectory) {
        this.directory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null").resolve(".dbgit");
        this.headFile = directory.resolve("HEAD");
        this.configFile = directory.resolve("config.json");
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
            throw new RepositoryException("Could not read '" + configFile + "': " + exception.getMessage(), exception);
        }
    }

    private void writeConfig(ObjectNode root) {
        try {
            Files.writeString(configFile, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + System.lineSeparator());
        } catch (IOException exception) {
            throw new RepositoryException("Could not update '" + configFile + "': " + exception.getMessage(), exception);
        }
    }

    /** The checked-out branch, defaulting to {@code main} in a working directory dbgit hasn't been used in yet. */
    public String currentBranch() {
        initialize();
        return read().trim();
    }

    public void checkout(String branch) {
        Objects.requireNonNull(branch, "branch must not be null");
        initialize();
        write(branch);
    }

    /** Creates {@code .dbgit/HEAD} pointing at the default branch, unless this directory already has one. */
    private void initialize() {
        if (Files.exists(directory)) {
            return;
        }
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new RepositoryException("Could not create '" + directory + "': " + exception.getMessage(), exception);
        }
        write(DEFAULT_BRANCH);
    }

    private String read() {
        try {
            return Files.readString(headFile);
        } catch (IOException exception) {
            throw new RepositoryException("Could not read '" + headFile + "': " + exception.getMessage(), exception);
        }
    }

    private void write(String branch) {
        try {
            Files.writeString(headFile, branch + System.lineSeparator());
        } catch (IOException exception) {
            throw new RepositoryException("Could not update '" + headFile + "': " + exception.getMessage(), exception);
        }
    }
}
