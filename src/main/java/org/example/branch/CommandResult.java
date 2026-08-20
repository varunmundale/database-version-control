package org.example.branch;

/** Captured outcome of a local process invocation. */
public record CommandResult(int exitCode, String output) {
    public boolean succeeded() {
        return exitCode == 0;
    }
}
