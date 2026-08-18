package org.example.dbgit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Lightweight local dbgit state stored under {@code .dbgit}. */
final class DbGitRepository {
    private static final String DEFAULT_BRANCH = "main";
    private final Path directory;
    private final Path headFile;
    private final Path branchesFile;

    DbGitRepository(Path workingDirectory) {
        directory = workingDirectory.resolve(".dbgit");
        headFile = directory.resolve("HEAD");
        branchesFile = directory.resolve("branches");
    }

    void initialize() throws IOException {
        if (Files.exists(directory)) {
            return;
        }
        Files.createDirectories(directory);
        Files.writeString(headFile, DEFAULT_BRANCH + System.lineSeparator());
        Files.writeString(branchesFile, DEFAULT_BRANCH + System.lineSeparator());
    }

    String currentBranch() throws IOException {
        initialize();
        return Files.readString(headFile).trim();
    }

    List<String> branches() throws IOException {
        initialize();
        return Files.readAllLines(branchesFile).stream().filter(branch -> !branch.isBlank()).toList();
    }

    boolean branchExists(String branch) throws IOException {
        return branches().contains(branch);
    }

    void createAndCheckout(String branch) throws IOException {
        List<String> currentBranches = branches();
        if (currentBranches.contains(branch)) {
            throw new IllegalArgumentException("Branch already exists: " + branch);
        }
        Set<String> updatedBranches = new LinkedHashSet<>(currentBranches);
        updatedBranches.add(branch);
        Files.write(branchesFile, updatedBranches);
        checkout(branch);
    }

    void checkout(String branch) throws IOException {
        if (!branches().contains(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        Files.writeString(headFile, branch + System.lineSeparator());
    }
}
