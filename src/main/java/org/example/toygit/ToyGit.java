package org.example.toygit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Minimal, in-memory Git-like operations on complete file snapshots.
 * Diff is file-level; merge is a three-way merge using a common base commit.
 */
public final class ToyGit {
    private ToyGit() {
    }

    public static List<FileChange> diff(Commit from, Commit to) {
        Objects.requireNonNull(from, "from commit must not be null");
        Objects.requireNonNull(to, "to commit must not be null");

        List<FileChange> changes = new ArrayList<>();
        for (String path : allPaths(from, to)) {
            String oldContent = from.files().get(path);
            String newContent = to.files().get(path);

            if (Objects.equals(oldContent, newContent)) {
                continue;
            }
            if (oldContent == null) {
                changes.add(new FileChange(path, ChangeType.ADDED, null, newContent));
            } else if (newContent == null) {
                changes.add(new FileChange(path, ChangeType.DELETED, oldContent, null));
            } else {
                changes.add(new FileChange(path, ChangeType.MODIFIED, oldContent, newContent));
            }
        }
        return List.copyOf(changes);
    }

    public static MergeResult merge(Commit base, Commit ours, Commit theirs) {
        Objects.requireNonNull(base, "base commit must not be null");
        Objects.requireNonNull(ours, "ours commit must not be null");
        Objects.requireNonNull(theirs, "theirs commit must not be null");

        Map<String, String> mergedFiles = new TreeMap<>();
        List<String> conflicts = new ArrayList<>();
        for (String path : allPaths(base, ours, theirs)) {
            String baseContent = base.files().get(path);
            String ourContent = ours.files().get(path);
            String theirContent = theirs.files().get(path);
            String mergedContent;

            if (Objects.equals(ourContent, theirContent)) {
                mergedContent = ourContent;
            } else if (Objects.equals(ourContent, baseContent)) {
                mergedContent = theirContent;
            } else if (Objects.equals(theirContent, baseContent)) {
                mergedContent = ourContent;
            } else {
                mergedContent = conflictMarkers(ourContent, theirContent);
                conflicts.add(path);
            }

            if (mergedContent != null) {
                mergedFiles.put(path, mergedContent);
            }
        }
        return new MergeResult(mergedFiles, conflicts);
    }

    private static Set<String> allPaths(Commit... commits) {
        Set<String> paths = new TreeSet<>();
        for (Commit commit : commits) {
            paths.addAll(commit.files().keySet());
        }
        return paths;
    }

    private static String conflictMarkers(String ours, String theirs) {
        return "<<<<<<< OURS\n" + contentOrEmpty(ours)
                + "=======\n" + contentOrEmpty(theirs)
                + ">>>>>>> THEIRS\n";
    }

    private static String contentOrEmpty(String content) {
        return content == null || content.endsWith("\n") ? Objects.toString(content, "") : content + "\n";
    }
}
