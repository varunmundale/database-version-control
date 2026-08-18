package org.example;

import org.example.toygit.Commit;
import org.example.toygit.ToyGit;

import java.util.Map;

public class Main {
    static void main() {
        Commit initial = new Commit("initial", Map.of("readme.md", "Hello\n"));
        Commit updated = new Commit("updated", Map.of("readme.md", "Hello, Toy Git!\n"));

        ToyGit.diff(initial, updated).forEach(IO::println);
    }
}
