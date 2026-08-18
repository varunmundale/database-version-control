package org.example.branch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Command runner backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {
    @Override
    public CommandResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new CommandResult(process.waitFor(), output);
    }
}
