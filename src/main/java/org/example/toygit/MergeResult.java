package org.example.toygit;

import java.util.List;
import java.util.Map;

/** The merged snapshot and the paths that required conflict markers. */
public record MergeResult(Map<String, String> files, List<String> conflicts) {
    public MergeResult {
        files = Map.copyOf(files);
        conflicts = List.copyOf(conflicts);
    }

    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }
}
