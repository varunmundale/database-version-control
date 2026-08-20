package org.example.service;

import java.util.List;

/** Lines emitted by one dbgit command. */
public record DbGitCommandResult(List<String> lines) {
    public DbGitCommandResult {
        lines = List.copyOf(lines);
    }
}
