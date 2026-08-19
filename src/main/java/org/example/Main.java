package org.example;

import org.example.dbgit.DbGitClient;

import java.util.List;

public class Main {
    public static void main(String[] args) {
        int exitCode = new DbGitClient().run(List.of(args), System.out, System.err);
        System.exit(exitCode);
    }
}
