package org.example.service.command;

import org.example.diff.ColumnDiff;
import org.example.diff.DatabaseDiff;
import org.example.diff.Side;
import org.example.diff.TableDiff;
import org.example.model.schema.TableModel;
import org.example.service.DbGitCommandResult;
import org.example.versioning.BranchMetadataStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code dbgit diff <left> <right>} - compares two branches' committed schemas, replayed purely in memory, and
 * renders the resulting {@link TableDiff} list as CLI text. Rendering lives here, not in the diff model itself, so
 * the model stays a plain description of what differs, usable by any other presentation later.
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

        Map<String, TableModel> leftSchema = context.schemaReplayer().replay(metadataStore.commitHistory(left));
        Map<String, TableModel> rightSchema = context.schemaReplayer().replay(metadataStore.commitHistory(right));
        List<TableDiff> tableDiffs = databaseDiff.diff(leftSchema.values(), rightSchema.values());

        if (tableDiffs.isEmpty()) {
            return print(List.of("No differences between '" + left + "' and '" + right + "'."));
        }
        List<String> lines = new ArrayList<>();
        for (TableDiff tableDiff : tableDiffs) {
            describeTable(tableDiff, lines);
        }
        return print(lines);
    }

    private static void describeTable(TableDiff tableDiff, List<String> lines) {
        if (tableDiff.side() != Side.CONFLICT) {
            lines.add(marker(tableDiff.side()) + " table " + tableDiff.tableName());
        }
        for (ColumnDiff columnDiff : tableDiff.columnDiffs()) {
            lines.add(marker(columnDiff.side()) + " " + describeColumn(tableDiff.tableName(), columnDiff));
        }
    }

    private static String describeColumn(String tableName, ColumnDiff columnDiff) {
        String label = "column " + tableName + "." + columnDiff.columnName();
        if (columnDiff.side() != Side.CONFLICT) {
            return label;
        }
        if (columnDiff.isRename()) {
            return "column " + tableName + "." + columnDiff.left().name() + " renamed to '" + columnDiff.right().name()
                    + "' on the other side (left: " + columnDiff.left().definition()
                    + ", right: " + columnDiff.right().definition() + ")";
        }
        return label + " (left: " + columnDiff.left().definition() + ", right: " + columnDiff.right().definition() + ")";
    }

    private static String marker(Side side) {
        return switch (side) {
            case LEFT -> "<";
            case RIGHT -> ">";
            case CONFLICT -> "!";
        };
    }
}
