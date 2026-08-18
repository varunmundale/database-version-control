package org.example.toygit;

import java.util.Map;
import java.util.Objects;

/** An immutable snapshot of a repository at a point in time. */
public record Commit(String id, Map<String, String> files) {
    public Commit {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(files, "files must not be null");
        files = Map.copyOf(files);
    }
}
