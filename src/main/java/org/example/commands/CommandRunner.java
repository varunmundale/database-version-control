package org.example.commands;

import java.io.IOException;
import java.util.List;

/** Runs a process command; injecting this interface keeps Docker orchestration testable. */
@FunctionalInterface
public interface CommandRunner {
    CommandResult run(List<String> command) throws IOException, InterruptedException;
}
