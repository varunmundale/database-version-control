package org.example.service;

import org.example.branch.BranchFork;
import org.example.branch.DbGitRepository;
import org.example.connector.SqlConnector;
import org.example.ddl.DdlStatementParser;
import org.example.diff.SchemaDiff;
import org.example.model.schema.TableModel;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;
import org.example.replay.SchemaReplayer;
import org.example.versioning.BranchMetadataStore;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Command service supporting {@code dbgit checkout}, {@code dbgit branch}, {@code dbgit add} and {@code dbgit commit}. */
public final class DbGitService {
    private static final String SCHEMA = "public";

    private final DbGitRepository repository;
    private final BranchFork branchFork;
    private final DdlStatementParser ddlParser = new DdlStatementParser();
    private final SchemaReplayer schemaReplayer = new SchemaReplayer(SCHEMA);

    public DbGitService(Path workingDirectory) {
        this(workingDirectory, new BranchFork());
    }

    public DbGitService(Path workingDirectory, BranchFork branchFork) {
        repository = new DbGitRepository(Objects.requireNonNull(workingDirectory, "workingDirectory must not be null"));
        this.branchFork = Objects.requireNonNull(branchFork, "branchFork must not be null");
    }

    public DbGitCommandResult execute(String commandLine) {
        Objects.requireNonNull(commandLine, "commandLine must not be null");
        return execute(Arrays.stream(commandLine.trim().split("\\s+")).toList());
    }

    public DbGitCommandResult execute(List<String> arguments) {
        try {
            if (arguments.equals(List.of("dbgit", "branch"))) {
                return print(branchLines());
            }
            if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")
                    && arguments.get(2).equals("-b")) {
                return createAndCheckout(arguments.get(3));
            }
            if (arguments.size() == 3 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("checkout")) {
                return checkout(arguments.get(2));
            }
            if (arguments.equals(List.of("dbgit", "commit"))) {
                return commit();
            }
            if (arguments.size() == 4 && arguments.get(0).equals("dbgit") && arguments.get(1).equals("diff")) {
                return diff(arguments.get(2), arguments.get(3));
            }
            throw new IllegalArgumentException(
                    "Usage: dbgit checkout -b <branch> | dbgit checkout <branch> | dbgit branch | dbgit add | dbgit commit "
                            + "| dbgit diff <branch1> <branch2>");
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update local .dbgit state", exception);
        }
    }

    public DbGitCommandResult add(String ddl) {
        Objects.requireNonNull(ddl, "ddl must not be null");
        String statement = ddl.strip();
        if (statement.isEmpty()) {
            throw new IllegalArgumentException("Usage: dbgit add <DDL statement>");
        }
        try {
            String branch = repository.currentBranch();
            BranchMetadataStore metadataStore = branchFork.metadataStore();

            String tableName = ddlParser.tableName(statement);
            Map<String, TableModel> internalSchema = currentSchema(branch, metadataStore);
            TableModel updated = ddlParser.apply(SCHEMA, statement, internalSchema.get(tableName));

            long changesetId = metadataStore.stageChangeset(branch, statement);

            String database = branchFork.defaultDatabaseName(branch);
            try (SqlConnector connector = branchFork.connect(database)) {
                connector.execute(statement);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not apply DDL to database '" + database + "': " + exception.getMessage(), exception);
            }

            metadataStore.markApplied(changesetId);
            return print(List.of("Applied changeset #" + changesetId + " for branch '" + branch + "': table '" + updated.name()
                    + "' now has " + updated.columns().size() + " column(s)."));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update local .dbgit state", exception);
        }
    }

    private DbGitCommandResult commit() throws IOException {
        String branch = repository.currentBranch();
        BranchMetadataStore metadataStore = branchFork.metadataStore();
        List<Long> appliedChangesetIds = metadataStore.changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .map(ChangeSet::id)
                .toList();
        if (appliedChangesetIds.isEmpty()) {
            return print(List.of("Nothing to commit for branch '" + branch + "'."));
        }
        long commitId = metadataStore.commit(branch, appliedChangesetIds);
        return print(List.of("Created commit #" + commitId + " for branch '" + branch + "' with "
                + appliedChangesetIds.size() + " changeset(s)."));
    }

    private DbGitCommandResult diff(String left, String right) {
        BranchMetadataStore metadataStore = branchFork.metadataStore();
        if (!metadataStore.branches().contains(left)) {
            throw new IllegalArgumentException("Unknown branch: " + left);
        }
        if (!metadataStore.branches().contains(right)) {
            throw new IllegalArgumentException("Unknown branch: " + right);
        }

        Map<String, TableModel> leftSchema = schemaReplayer.replay(metadataStore.commitHistory(left), new LinkedHashMap<>());
        Map<String, TableModel> rightSchema = schemaReplayer.replay(metadataStore.commitHistory(right), new LinkedHashMap<>());
        List<SchemaDiff.Entry> diffEntries = SchemaDiff.diff(leftSchema.values(), rightSchema.values());

        if (diffEntries.isEmpty()) {
            return print(List.of("No differences between '" + left + "' and '" + right + "'."));
        }
        List<String> lines = new ArrayList<>();
        for (SchemaDiff.Entry entry : diffEntries) {
            lines.add(marker(entry.side()) + " " + entry.description());
        }
        return print(lines);
    }

    private static String marker(SchemaDiff.Side side) {
        return switch (side) {
            case LEFT -> "<";
            case RIGHT -> ">";
            case CONFLICT -> "!";
        };
    }

    /**
     * The branch's current internal schema: its inherited/committed history (reached via the shared commit chain,
     * regardless of which branch originally created each commit) plus its own not-yet-committed applied changesets.
     */
    private Map<String, TableModel> currentSchema(String branch, BranchMetadataStore metadataStore) {
        Map<String, TableModel> schema = schemaReplayer.replay(metadataStore.commitHistory(branch), new LinkedHashMap<>());
        List<ChangeSet> ownApplied = metadataStore.changesetsForBranch(branch).stream()
                .filter(changeset -> changeset.status() == ChangesetStatus.APPLIED)
                .toList();
        return schemaReplayer.replay(ownApplied, schema);
    }

    private DbGitCommandResult createAndCheckout(String branch) throws IOException {
        String fromBranch = repository.currentBranch();
        branchFork.fork(fromBranch, branch);
        repository.checkout(branch);
        return print(List.of("Switched to a new branch '" + branch + "'."));
    }

    private DbGitCommandResult checkout(String branch) throws IOException {
        if (!branchFork.metadataStore().branches().contains(branch)) {
            throw new IllegalArgumentException("Unknown branch: " + branch);
        }
        repository.checkout(branch);
        return print(List.of("Switched to branch '" + branch + "'."));
    }

    private List<String> branchLines() throws IOException {
        String currentBranch = repository.currentBranch();
        List<String> lines = new ArrayList<>();
        for (String branch : branchFork.metadataStore().branches()) {
            lines.add((branch.equals(currentBranch) ? "* " : "  ") + branch);
        }
        return lines;
    }

    private static DbGitCommandResult print(List<String> lines) {
        lines.forEach(System.out::println);
        return new DbGitCommandResult(lines);
    }
}
