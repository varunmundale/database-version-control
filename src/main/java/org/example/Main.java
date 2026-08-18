package org.example;

import org.example.dbgit.DbGitService;

import java.nio.file.Path;

public class Main {
    public static void main(String[] args) {
        DbGitService dbGitService = new DbGitService(Path.of("."));
        dbGitService.execute(String.join(" ", args));
    }
}
