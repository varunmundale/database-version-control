package org.example.versioning;

import org.example.config.ConnectionSettings;
import org.example.connector.ConnectorFactory;
import org.example.connector.SqlConnector;
import org.example.connector.SqlExecutionResult;
import org.example.model.versioning.ChangeSet;
import org.example.model.versioning.ChangesetStatus;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Stores branch, changeset and commit metadata in a standalone PostgreSQL server, separate from the branch databases themselves. */
public final class PostgresBranchMetadataStore implements BranchMetadataStore {
    private static final String DEFAULT_BRANCH = "main";

    private final ConnectorFactory connectorFactory;
    private final ConnectionSettings adminSettings;
    private final ConnectionSettings settings;

    public PostgresBranchMetadataStore(ConnectorFactory connectorFactory, ConnectionSettings adminSettings, ConnectionSettings settings) {
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
        this.adminSettings = Objects.requireNonNull(adminSettings, "adminSettings must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        ensureDatabaseExists();
    }

    @Override
    public List<String> branches() {
        ensureSchema();
        SqlExecutionResult result = execute("SELECT branch_name FROM branch_metadata ORDER BY branch_name");
        return result.rows().stream().map(row -> String.valueOf(row.get("branch_name"))).toList();
    }

    @Override
    public boolean createBranch(String branchName, String forkedFrom) {
        ensureSchema();
        SqlExecutionResult result = forkedFrom == null
                ? execute("INSERT INTO branch_metadata (branch_name, forked_from) VALUES ("
                        + quote(branchName) + ", NULL) ON CONFLICT (branch_name) DO NOTHING")
                : execute("INSERT INTO branch_metadata (branch_name, forked_from, head_commit_id) "
                        + "SELECT " + quote(branchName) + ", " + quote(forkedFrom) + ", head_commit_id "
                        + "FROM branch_metadata WHERE branch_name = " + quote(forkedFrom)
                        + " ON CONFLICT (branch_name) DO NOTHING");
        return result.updateCount() > 0;
    }

    @Override
    public long stageChangeset(String branch, String ddl) {
        ensureSchema();
        SqlExecutionResult result = execute("INSERT INTO branch_changesets (branch_name, ddl, status) VALUES ("
                + quote(branch) + ", " + quote(ddl) + ", 'PENDING') RETURNING id");
        return id(result);
    }

    @Override
    public void markApplied(long changesetId) {
        ensureSchema();
        execute("UPDATE branch_changesets SET status = 'APPLIED' WHERE id = " + changesetId);
    }

    @Override
    public List<ChangeSet> changesetsForBranch(String branch) {
        ensureSchema();
        SqlExecutionResult result = execute("SELECT id, ddl, status, applied_at FROM branch_changesets WHERE branch_name = "
                + quote(branch) + " ORDER BY id");
        return toChangesets(branch, result);
    }

    @Override
    public List<ChangeSet> commitHistory(String branch) {
        ensureSchema();
        SqlExecutionResult result = execute("WITH RECURSIVE chain AS ("
                + "SELECT id, parent_commit_id, 0 AS depth FROM branch_commits "
                + "WHERE id = (SELECT head_commit_id FROM branch_metadata WHERE branch_name = " + quote(branch) + ") "
                + "UNION ALL "
                + "SELECT c.id, c.parent_commit_id, chain.depth + 1 FROM branch_commits c JOIN chain ON c.id = chain.parent_commit_id"
                + ") SELECT bc.id, bc.ddl, bc.status, bc.applied_at FROM branch_changesets bc "
                + "JOIN chain ON bc.commit_id = chain.id "
                + "ORDER BY chain.depth DESC, bc.id ASC");
        return toChangesets(branch, result);
    }

    @Override
    public long commit(String branch, List<Long> changesetIds) {
        if (changesetIds.isEmpty()) {
            throw new MetadataStoreException("No applied changesets to commit for branch '" + branch + "'.");
        }
        ensureSchema();
        try (SqlConnector connector = connectorFactory.connect(settings)) {
            Long commitId = connector.transaction(txn -> doCommit(txn, branch, changesetIds));
            return commitId;
        } catch (SQLException exception) {
            throw new MetadataStoreException("Could not commit branch '" + branch + "': " + exception.getMessage(), exception);
        }
    }

    /** Runs as a single transaction: the new commit row, the previous head's forward pointer, the branch's head, and the changesets it folds in must all move together. */
    private long doCommit(SqlConnector txn, String branch, List<Long> changesetIds) throws SQLException {
        Long headCommitId = headCommitId(txn, branch);
        SqlExecutionResult insertResult = txn.execute("INSERT INTO branch_commits (parent_commit_id) VALUES ("
                + (headCommitId == null ? "NULL" : headCommitId) + ") RETURNING id");
        long commitId = id(insertResult);

        if (headCommitId != null) {
            txn.execute("UPDATE branch_commits SET next_commit_id = " + commitId + " WHERE id = " + headCommitId);
        }
        txn.execute("UPDATE branch_metadata SET head_commit_id = " + commitId + " WHERE branch_name = " + quote(branch));
        String ids = changesetIds.stream().map(String::valueOf).collect(Collectors.joining(", "));
        txn.execute("UPDATE branch_changesets SET status = 'COMMIT', commit_id = " + commitId
                + " WHERE id IN (" + ids + ") AND status = 'APPLIED'");
        return commitId;
    }

    private Long headCommitId(SqlConnector txn, String branch) throws SQLException {
        SqlExecutionResult result = txn.execute("SELECT head_commit_id FROM branch_metadata WHERE branch_name = " + quote(branch));
        if (result == null || result.rows().isEmpty()) {
            return null;
        }
        Object value = result.rows().getFirst().get("head_commit_id");
        return value == null ? null : ((Number) value).longValue();
    }

    private void ensureDatabaseExists() {
        try (SqlConnector connector = connectorFactory.connect(adminSettings)) {
            SqlExecutionResult result = connector.execute("SELECT 1 FROM pg_database WHERE datname = " + quote(settings.database()));
            if (result.rows().isEmpty()) {
                connector.execute("CREATE DATABASE \"" + settings.database() + "\"");
            }
        } catch (SQLException exception) {
            throw new MetadataStoreException("Could not create metadata database '" + settings.database() + "': " + exception.getMessage(), exception);
        }
    }

    private void ensureSchema() {
        execute("CREATE TABLE IF NOT EXISTS branch_commits ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "parent_commit_id BIGINT REFERENCES branch_commits(id), next_commit_id BIGINT REFERENCES branch_commits(id), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                + "CREATE TABLE IF NOT EXISTS branch_metadata ("
                + "branch_name TEXT PRIMARY KEY, forked_from TEXT, head_commit_id BIGINT REFERENCES branch_commits(id), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                + "CREATE TABLE IF NOT EXISTS branch_changesets ("
                + "id BIGSERIAL PRIMARY KEY, branch_name TEXT NOT NULL REFERENCES branch_metadata(branch_name), "
                + "ddl TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', commit_id BIGINT REFERENCES branch_commits(id), "
                + "applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                + "INSERT INTO branch_metadata (branch_name, forked_from) VALUES (" + quote(DEFAULT_BRANCH) + ", NULL) "
                + "ON CONFLICT (branch_name) DO NOTHING;");
    }

    private static long id(SqlExecutionResult result) {
        return ((Number) result.rows().getFirst().get("id")).longValue();
    }

    private static List<ChangeSet> toChangesets(String branch, SqlExecutionResult result) {
        List<ChangeSet> changesets = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            changesets.add(new ChangeSet(
                    ((Number) row.get("id")).longValue(),
                    branch,
                    String.valueOf(row.get("ddl")),
                    ChangesetStatus.valueOf(String.valueOf(row.get("status"))),
                    toInstant(row.get("applied_at"))));
        }
        return changesets;
    }

    private static Instant toInstant(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }

    private SqlExecutionResult execute(String sql) {
        try (SqlConnector connector = connectorFactory.connect(settings)) {
            return connector.execute(sql);
        } catch (SQLException exception) {
            throw new MetadataStoreException("Could not update branch metadata: " + exception.getMessage(), exception);
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
