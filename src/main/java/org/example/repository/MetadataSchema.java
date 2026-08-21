package org.example.repository;

import org.jooq.DSLContext;

/** Creates the metadata store's tables if they don't exist yet, and seeds the {@code main} branch. Run once, by {@link MetadataDatabase}. */
final class MetadataSchema {
    private static final String DEFAULT_BRANCH = "main";

    private MetadataSchema() {
    }

    static void ensure(DSLContext ctx) {
        ctx.execute("CREATE TABLE IF NOT EXISTS branch_commits ("
                + "id BIGSERIAL PRIMARY KEY, "
                + "parent_commit_id BIGINT REFERENCES branch_commits(id), next_commit_id BIGINT REFERENCES branch_commits(id), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        ctx.execute("ALTER TABLE branch_commits ADD COLUMN IF NOT EXISTS second_parent_commit_id BIGINT REFERENCES branch_commits(id)");
        ctx.execute("CREATE TABLE IF NOT EXISTS branch_metadata ("
                + "branch_name TEXT PRIMARY KEY, forked_from TEXT, head_commit_id BIGINT REFERENCES branch_commits(id), "
                + "created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        ctx.execute("CREATE TABLE IF NOT EXISTS branch_changesets ("
                + "id BIGSERIAL PRIMARY KEY, branch_name TEXT NOT NULL REFERENCES branch_metadata(branch_name), "
                + "ddl TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'PENDING', commit_id BIGINT REFERENCES branch_commits(id), "
                + "applied_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        ctx.execute("CREATE TABLE IF NOT EXISTS tracked_databases ("
                + "branch_name TEXT PRIMARY KEY REFERENCES branch_metadata(branch_name), "
                + "signature TEXT NOT NULL, host TEXT NOT NULL, port INT NOT NULL, "
                + "database_name TEXT NOT NULL, db_user TEXT NOT NULL, "
                + "updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        ctx.execute("INSERT INTO branch_metadata (branch_name, forked_from) VALUES ({0}, NULL) ON CONFLICT (branch_name) DO NOTHING",
                DEFAULT_BRANCH);
    }
}
