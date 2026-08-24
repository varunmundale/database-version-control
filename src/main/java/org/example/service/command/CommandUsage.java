package org.example.service.command;

import java.util.Objects;

/**
 * One command's documentation: the verb {@code dbgit help} matches against, its synopsis and description. Every
 * concrete {@link Command} declares its own {@code public static final CommandUsage USAGE}, so {@link HelpCommand}
 * and {@link CommandFactory} can't drift apart on it.
 */
public record CommandUsage(String verb, String synopsis, String description) {
    public CommandUsage {
        Objects.requireNonNull(verb, "verb must not be null");
        Objects.requireNonNull(synopsis, "synopsis must not be null");
        Objects.requireNonNull(description, "description must not be null");
    }
}
