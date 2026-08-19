package org.example.branch;

import org.example.database.SqlConnector;
import org.example.database.SqlExecutionResult;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stores branch and database fork metadata in a standalone PostgreSQL server, separate from the per-branch databases themselves. */
public final class PostgresBranchMetadataStore implements BranchMetadataStore {
    private static final String DEFAULT_BRANCH = "main";

    private final PostgresConnectorFactory connectorFactory;
    private final String adminDatabase;
    private final String database;

    public PostgresBranchMetadataStore(PostgresConnectorFactory connectorFactory, String adminDatabase, String database) {
        this.connectorFactory = Objects.requireNonNull(connectorFactory, "connectorFactory must not be null");
        this.adminDatabase = Objects.requireNonNull(adminDatabase, "adminDatabase must not be null");
        this.database = Objects.requireNonNull(database, "database must not be null");
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
        String forkedFromValue = forkedFrom == null ? "NULL" : quote(forkedFrom);
        SqlExecutionResult result = execute("INSERT INTO branch_metadata (branch_name, forked_from) VALUES ("
                + quote(branchName) + ", " + forkedFromValue + ") ON CONFLICT (branch_name) DO NOTHING");
        return result.updateCount() > 0;
    }

    @Override
    public List<BranchDatabase> databasesForBranch(String branch) {
        ensureSchema();
        SqlExecutionResult result = execute("SELECT logical_name, database_name FROM branch_databases WHERE branch_name = "
                + quote(branch) + " ORDER BY logical_name");
        List<BranchDatabase> databases = new ArrayList<>();
        for (Map<String, Object> row : result.rows()) {
            databases.add(new BranchDatabase(String.valueOf(row.get("logical_name")), String.valueOf(row.get("database_name"))));
        }
        return databases;
    }

    @Override
    public void recordDatabases(String branch, List<BranchDatabase> databases) {
        if (databases.isEmpty()) {
            return;
        }
        ensureSchema();
        StringBuilder sql = new StringBuilder();
        for (BranchDatabase database : databases) {
            sql.append("INSERT INTO branch_databases (branch_name, logical_name, database_name) VALUES (")
                    .append(quote(branch)).append(", ").append(quote(database.logicalName())).append(", ")
                    .append(quote(database.databaseName()))
                    .append(") ON CONFLICT (branch_name, logical_name) DO UPDATE SET database_name = EXCLUDED.database_name; ");
        }
        execute(sql.toString());
    }

    @Override
    public void recordChangeset(ChangeSet changeset) {
        ensureSchema();
        execute("INSERT INTO branch_changesets (branch_name, ddl, applied_at) VALUES ("
                + quote(changeset.branch()) + ", " + quote(changeset.ddl()) + ", " + quote(changeset.appliedAt().toString()) + ")");
    }

    @Override
    public List<String> changesetsForBranch(String branch) {
        ensureSchema();
        SqlExecutionResult result = execute("SELECT ddl FROM branch_changesets WHERE branch_name = "
                + quote(branch) + " ORDER BY id");
        return result.rows().stream().map(row -> String.valueOf(row.get("ddl"))).toList();
    }

    private void ensureDatabaseExists() {
        try (SqlConnector connector = connectorFactory.connect(adminDatabase)) {
            SqlExecutionResult result = connector.execute("SELECT 1 FROM pg_database WHERE datname = " + quote(database));
            if (result.rows().isEmpty()) {
                connector.execute("CREATE DATABASE \"" + database + "\"");
            }
        } catch (SQLException exception) {
            throw new BranchForkException("Could not create metadata database '" + database + "': " + exception.getMessage(), exception);
        }
    }

    private void ensureSchema() {
        execute("CREATE TABLE IF NOT EXISTS branch_metadata ("
                + "branch_name TEXT PRIMARY KEY, forked_from TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP); "
                + "CREATE TABLE IF NOT EXISTS branch_databases ("
                + "branch_name TEXT NOT NULL REFERENCES branch_metadata(branch_name), "
                + "logical_name TEXT NOT NULL, database_name TEXT NOT NULL, "
                + "PRIMARY KEY (branch_name, logical_name)); "
                + "CREATE TABLE IF NOT EXISTS branch_changesets ("
                + "id BIGSERIAL PRIMARY KEY, branch_name TEXT NOT NULL REFERENCES branch_metadata(branch_name), "
                + "ddl TEXT NOT NULL, applied_at TIMESTAMPTZ NOT NULL); "
                + "INSERT INTO branch_metadata (branch_name, forked_from) VALUES (" + quote(DEFAULT_BRANCH) + ", NULL) "
                + "ON CONFLICT (branch_name) DO NOTHING;");
    }

    private SqlExecutionResult execute(String sql) {
        try (SqlConnector connector = connectorFactory.connect(database)) {
            return connector.execute(sql);
        } catch (SQLException exception) {
            throw new BranchForkException("Could not update branch metadata: " + exception.getMessage(), exception);
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
