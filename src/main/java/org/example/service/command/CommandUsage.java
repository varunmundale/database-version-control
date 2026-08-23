package org.example.service.command;

import java.util.Objects;

/**
 * One command's documentation: the verb {@code dbgit help} matches against, its synopsis and a one-line
 * description. Every concrete {@link Command} declares its own {@code public static final CommandUsage USAGE},
 * which is what {@link HelpCommand} lists and {@link CommandFactory} points to on a bad command line - one
 * constant per command rather than a hand-maintained string elsewhere, so the two can't drift apart. A
 * reflection-based test enforces that every command actually declares one.
 */
public record CommandUsage(String verb, String synopsis, String description) {
    public CommandUsage {
        Objects.requireNonNull(verb, "verb must not be null");
        Objects.requireNonNull(synopsis, "synopsis must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }
}
