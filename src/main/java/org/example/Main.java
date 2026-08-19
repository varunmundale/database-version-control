package org.example;

import org.example.dbgit.DbGitClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Main {
    public static void main(String[] args) {
        DbGitClient client = new DbGitClient();
        int exitCode = args.length >= 1 && args[0].equals("add")
                ? client.runAdd(readStdin(), System.out, System.err)
                : client.run(List.of(args), System.out, System.err);
        System.exit(exitCode);
    }

    private static String readStdin() {
        try {
            return new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
