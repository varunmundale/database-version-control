package org.example.toygit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToyGitTest {
    @Test
    void diffReportsAddedModifiedAndDeletedFilesInPathOrder() {
        Commit from = commit("from", Map.of("delete.txt", "old", "edit.txt", "before", "same.txt", "same"));
        Commit to = commit("to", Map.of("add.txt", "new", "edit.txt", "after", "same.txt", "same"));

        assertEquals(List.of(
                new FileChange("add.txt", ChangeType.ADDED, null, "new"),
                new FileChange("delete.txt", ChangeType.DELETED, "old", null),
                new FileChange("edit.txt", ChangeType.MODIFIED, "before", "after")
        ), ToyGit.diff(from, to));
    }

    @Test
    void mergeCombinesIndependentChanges() {
        Commit base = commit("base", Map.of("readme.md", "base", "settings.ini", "v1"));
        Commit ours = commit("ours", Map.of("readme.md", "ours", "settings.ini", "v1"));
        Commit theirs = commit("theirs", Map.of("readme.md", "base", "settings.ini", "v2"));

        MergeResult result = ToyGit.merge(base, ours, theirs);

        assertEquals(Map.of("readme.md", "ours", "settings.ini", "v2"), result.files());
        assertFalse(result.hasConflicts());
        assertEquals(List.of(), result.conflicts());
    }

    @Test
    void mergeAcceptsIdenticalChangesMadeOnBothBranches() {
        Commit base = commit("base", Map.of("app.txt", "version 1"));
        Commit changed = commit("changed", Map.of("app.txt", "version 2"));

        MergeResult result = ToyGit.merge(base, changed, changed);

        assertEquals(Map.of("app.txt", "version 2"), result.files());
        assertFalse(result.hasConflicts());
    }

    @Test
    void mergePreservesDeletionWhenOnlyOneBranchDeletesTheUnchangedFile() {
        Commit base = commit("base", Map.of("obsolete.txt", "remove me"));
        Commit ours = commit("ours", Map.of());
        Commit theirs = commit("theirs", Map.of("obsolete.txt", "remove me"));

        MergeResult result = ToyGit.merge(base, ours, theirs);

        assertEquals(Map.of(), result.files());
        assertFalse(result.hasConflicts());
    }

    @Test
    void mergeWritesConflictMarkersWhenBothBranchesDifferFromBase() {
        Commit base = commit("base", Map.of("readme.md", "original\n"));
        Commit ours = commit("ours", Map.of("readme.md", "our edit\n"));
        Commit theirs = commit("theirs", Map.of("readme.md", "their edit\n"));

        MergeResult result = ToyGit.merge(base, ours, theirs);

        assertTrue(result.hasConflicts());
        assertEquals(List.of("readme.md"), result.conflicts());
        assertEquals("<<<<<<< OURS\nour edit\n=======\ntheir edit\n>>>>>>> THEIRS\n", result.files().get("readme.md"));
    }

    private static Commit commit(String id, Map<String, String> files) {
        return new Commit(id, files);
    }
}
