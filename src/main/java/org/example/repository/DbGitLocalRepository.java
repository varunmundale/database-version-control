package org.example.repository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * The local half of dbgit's state: a {@code .dbgit/HEAD} file naming the branch this working directory has checked
 * out. Everything else - which branches exist, what they contain - lives in the metadata store, so this stays a
 * single line of text.
 *
 * <p>Unlike its siblings in this package it is not a singleton: it is scoped to one working directory, and a
 * process can serve more than one.
 */
public final class DbGitLocalRepository {
    private static final String DEFAULT_BRANCH = "main";

    private final Path directory;
    private final Path headFile;

    public DbGitLocalRepository(Path workingDirectory) {
        this.directory = Objects.requireNonNull(workingDirectory, "workingDirectory must not be null").resolve(".dbgit");
        this.headFile = directory.resolve("HEAD");
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
