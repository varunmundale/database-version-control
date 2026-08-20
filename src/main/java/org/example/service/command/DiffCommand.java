package org.example.service.command;

import org.example.diff.DatabaseDiff;
import org.example.diff.HistoryDiffFormatter;
import org.example.model.versioning.ChangeSet;
import org.example.service.DbGitCommandResult;
import org.example.versioning.BranchMetadataStore;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit diff <left> <right>} - prints the two branches' commit-history divergence as a tree via
 * {@link HistoryDiffFormatter}: one node per table, one node per column that actually differs, and every
 * statement run against that column on each side nested underneath it. A column {@link DatabaseDiff} finds
 * genuinely conflicting (matched by stable id, so a rename on one side racing a modification on the other still
 * counts) is labeled as such, with both sides' statements brought together in the same place.
 */
public final class DiffCommand extends Command {
    private final DatabaseDiff databaseDiff = new DatabaseDiff();
    private final String left;
    private final String right;

    public DiffCommand(CommandContext context, String left, String right) {
        super(context);
        this.left = Objects.requireNonNull(left, "left must not be null");
        this.right = Objects.requireNonNull(right, "right must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        BranchMetadataStore metadataStore = context.branchFork().metadataStore();
        if (!metadataStore.branches().contains(left)) {
            throw new IllegalArgumentException("Unknown branch: " + left);
        }
        if (!metadataStore.branches().contains(right)) {
            throw new IllegalArgumentException("Unknown branch: " + right);
        }

        List<ChangeSet> leftHistory = metadataStore.commitHistory(left);
        List<ChangeSet> rightHistory = metadataStore.commitHistory(right);
        HistoryDiffFormatter historyDiffFormatter = new HistoryDiffFormatter(context.schemaReplayer(), databaseDiff);
        List<String> lines = historyDiffFormatter.format(left, right, leftHistory, rightHistory);

        if (lines.isEmpty()) {
            return print(List.of("No differences between '" + left + "' and '" + right + "'."));
        }
        return print(lines);
    }
}
