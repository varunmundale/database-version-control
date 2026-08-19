package org.example.dbgit;

import org.example.branch.BranchFork;
import org.example.branch.BranchMetadataStore;
import org.example.branch.ChangeSet;
import org.example.database.SqlConnector;
import org.example.schema.DdlStatementParser;
import org.example.schema.TableModel;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Command service supporting {@code dbgit checkout}, {@code dbgit branch} and {@code dbgit add}. */
public final class DbGitService {
    private static final String SCHEMA = "public";

    private final DbGitRepository repository;
    private final BranchFork branchFork;
    private final DdlStatementParser ddlParser = new DdlStatementParser();

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
            throw new IllegalArgumentException("Usage: dbgit checkout -b <branch> | dbgit checkout <branch> | dbgit branch | dbgit add");
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
            Map<String, TableModel> internalSchema = replay(metadataStore.changesetsForBranch(branch));
            TableModel updated = ddlParser.apply(SCHEMA, statement, internalSchema.get(tableName));

            String database = branchFork.defaultDatabaseName(branch);
            try (SqlConnector connector = branchFork.connect(database)) {
                connector.execute(statement);
            } catch (SQLException exception) {
                throw new IllegalStateException("Could not apply DDL to database '" + database + "': " + exception.getMessage(), exception);
            }

            metadataStore.recordChangeset(new ChangeSet(branch, statement, Instant.now()));
            return print(List.of("Recorded changeset for branch '" + branch + "': table '" + updated.name() + "' now has "
                    + updated.columns().size() + " column(s)."));
        } catch (IOException exception) {
            throw new IllegalStateException("Could not update local .dbgit state", exception);
        }
    }

    /** Rebuilds the branch's internal schema representation by replaying its previously recorded DDL statements. */
    private Map<String, TableModel> replay(List<String> priorStatements) {
        Map<String, TableModel> schema = new LinkedHashMap<>();
        for (String priorStatement : priorStatements) {
            String tableName = ddlParser.tableName(priorStatement);
            schema.put(tableName, ddlParser.apply(SCHEMA, priorStatement, schema.get(tableName)));
        }
        return schema;
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
