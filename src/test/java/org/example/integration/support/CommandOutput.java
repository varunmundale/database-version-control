package org.example.integration.support;

import java.util.List;

/**
 * What one {@code dbgit} invocation printed and returned - the same three things the shell scripts look at.
 *
 * <p>The two streams are kept apart because the client itself keeps them apart: an {@code OK} response is printed
 * to stdout and an {@code ERR} one to stderr, so which stream a line arrived on is part of what is being asserted.
 */
public record CommandOutput(int exitCode, List<String> out, List<String> err) {
    public CommandOutput {
        out = List.copyOf(out);
        err = List.copyOf(err);
    }

    public boolean succeeded() {
        return exitCode == 0;
    }

    public boolean failed() {
        return exitCode != 0;
    }

    /** Everything printed, whichever stream it came out on - for asserting that some line appeared at all. */
    public List<String> lines() {
        List<String> lines = new java.util.ArrayList<>(out);
        lines.addAll(err);
        return List.copyOf(lines);
    }

    public String text() {
        return String.join("\n", lines());
    }

    public boolean mentions(String fragment) {
        return lines().stream().anyMatch(line -> line.contains(fragment));
    }
}
