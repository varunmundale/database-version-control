package org.example.service.command;

import org.example.core.differ.DatabaseDiff;
import org.example.core.differ.Differ;
import org.example.core.differ.HistoryDiff;
import org.example.core.differ.HistoryDiffFormatter;
import org.example.models.versioning.CommitEntry;
import org.example.service.DbGitCommandResult;
import org.example.core.versioning.VersioningService;

import java.util.List;
import java.util.Objects;

/**
 * {@code dbgit diff <left> <right>} - walks both branches' commit histories through {@link Differ}, the one place
 * two branches are compared (a merge asks it the same question), and prints the result via
 * {@link HistoryDiffFormatter}.
 */
public final class DiffCommand extends Command {
    public static final CommandUsage USAGE = new CommandUsage("diff", "dbgit diff <branch1> <branch2>",
            "Compares two branches' schemas by walking their commit histories, printing one node per table and "
                    + "per column that actually differs.");

    private final HistoryDiffFormatter formatter = new HistoryDiffFormatter();
    private final String left;
    private final String right;

    public DiffCommand(CommandContext context, String left, String right) {
        super(context);
        this.left = Objects.requireNonNull(left, "left must not be null");
        this.right = Objects.requireNonNull(right, "right must not be null");
    }

    @Override
    public DbGitCommandResult execute() {
        VersioningService versioningService = context.versioningService();
        versioningService.requireBranchExists(left);
        versioningService.requireBranchExists(right);

        List<CommitEntry> leftCommits = versioningService.commits(left);
        List<CommitEntry> rightCommits = versioningService.commits(right);
        Differ differ = new Differ(context.replayer(), new DatabaseDiff());
        HistoryDiff diff = differ.diff(leftCommits, rightCommits);

        List<String> lines = formatter.format(left, right, diff);
        if (lines.isEmpty()) {
            return print(List.of("No differences between '" + left + "' and '" + right + "'."));
        }
        return print(lines);
    }
}
