package org.example.dbgit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Local pointer to the currently checked-out branch, stored under {@code .dbgit}. The set of branches itself is tracked in dbgit_metadata. */
final class DbGitRepository {
    private static final String DEFAULT_BRANCH = "main";
    private final Path directory;
    private final Path headFile;

    DbGitRepository(Path workingDirectory) {
        directory = workingDirectory.resolve(".dbgit");
        headFile = directory.resolve("HEAD");
    }

    void initialize() throws IOException {
        if (Files.exists(directory)) {
            return;
        }
        Files.createDirectories(directory);
        Files.writeString(headFile, DEFAULT_BRANCH + System.lineSeparator());
    }

    String currentBranch() throws IOException {
        initialize();
        return Files.readString(headFile).trim();
    }

    void checkout(String branch) throws IOException {
        initialize();
        Files.writeString(headFile, branch + System.lineSeparator());
    }
}
