package org.example.core.forker.docker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** Command runner backed by {@link ProcessBuilder}. */
public final class ProcessCommandRunner implements CommandRunner {
    /** Generous enough for a cold image pull; short enough that a wedged command cannot cost a thread forever. */
    private static final int TIMEOUT_MINUTES = 10;

    /** Bounded so a hung {@code docker} process can't hold a handler thread forever. */
    @Override
    public CommandResult run(List<String> command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        // Read before waiting: with the streams merged, a full pipe buffer would otherwise deadlock the wait.
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IOException("'" + String.join(" ", command) + "' did not finish within "
                    + TIMEOUT_MINUTES + " minutes and was killed.");
        }
        return new CommandResult(process.exitValue(), output);
    }
}
