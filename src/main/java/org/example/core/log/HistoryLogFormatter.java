package org.example.core.log;

import org.example.models.versioning.ChangeSet;
import org.example.models.versioning.Commit;
import org.example.models.versioning.CommitEntry;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link BranchLog} as the lines {@code dbgit log} prints: the working set first, since it is the newest
 * thing about a branch, then one entry per commit from HEAD backwards.
 *
 * <pre>
 * Branch 'feature/orders'
 *
 * Working set (1 uncommitted changeset(s)):
 *   #5 [APPLIED] ALTER TABLE orders ADD COLUMN note TEXT;
 *
 * commit #3
 * Author:     varun
 * Date:       2026-08-22T10:15:30Z
 * Message:    add a total column
 * Changesets:
 *   #4 ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);
 * </pre>
 *
 * <p>A changeset's DDL is collapsed onto one line - it is stored exactly as written, newlines included, and a log
 * is read line by line.
 */
public final class HistoryLogFormatter {

    public List<String> format(BranchLog log) {
        List<String> lines = new ArrayList<>();
        lines.add("Branch '" + log.branch() + "'");
        lines.add("");
        lines.addAll(workingSet(log.workingSet()));
        if (log.commits().isEmpty()) {
            lines.add("No commits on branch '" + log.branch() + "' yet.");
            return lines;
        }
        for (CommitEntry entry : log.commits()) {
            lines.add("");
            lines.addAll(commit(entry));
        }
        return lines;
    }

    private static List<String> workingSet(List<ChangeSet> workingSet) {
        if (workingSet.isEmpty()) {
            return List.of("Working set: clean.");
        }
        List<String> lines = new ArrayList<>();
        lines.add("Working set (" + workingSet.size() + " uncommitted changeset(s)):");
        for (ChangeSet changeset : workingSet) {
            lines.add("  #" + changeset.id() + " [" + changeset.status() + "] " + oneLine(changeset.ddl()));
        }
        return lines;
    }

    private static List<String> commit(CommitEntry entry) {
        Commit commit = entry.commit();
        List<String> lines = new ArrayList<>();
        lines.add("commit #" + commit.id() + (commit.isMerge() ? " (merge)" : ""));
        lines.add("Author:     " + commit.author());
        lines.add("Date:       " + commit.createdAt().truncatedTo(ChronoUnit.SECONDS));
        lines.add("Message:    " + (commit.message().isBlank() ? "(none)" : commit.message()));
        if (entry.changesets().isEmpty()) {
            lines.add("Changesets: (none)");
            return lines;
        }
        lines.add("Changesets:");
        for (ChangeSet changeset : entry.changesets()) {
            lines.add("  #" + changeset.id() + " " + oneLine(changeset.ddl()));
        }
        return lines;
    }

    private static String oneLine(String ddl) {
        return ddl.strip().replaceAll("\\s+", " ");
    }
}
