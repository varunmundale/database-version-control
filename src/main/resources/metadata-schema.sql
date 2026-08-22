-- The metadata store's tables. Applied on first use by MetadataSchema, and safe to re-apply:
-- every statement is idempotent. Statements are separated by semicolons at end of line.

CREATE TABLE IF NOT EXISTS branch_commits (
    id                      BIGSERIAL PRIMARY KEY,
    parent_commit_id        BIGINT REFERENCES branch_commits(id),
    second_parent_commit_id BIGINT REFERENCES branch_commits(id),
    next_commit_id          BIGINT REFERENCES branch_commits(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Metadata databases created before merge commits existed have no second parent column.
ALTER TABLE branch_commits
    ADD COLUMN IF NOT EXISTS second_parent_commit_id BIGINT REFERENCES branch_commits(id);

CREATE TABLE IF NOT EXISTS branch_metadata (
    branch_name    TEXT PRIMARY KEY,
    forked_from    TEXT,
    head_commit_id BIGINT REFERENCES branch_commits(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS branch_changesets (
    id          BIGSERIAL PRIMARY KEY,
    branch_name TEXT NOT NULL REFERENCES branch_metadata(branch_name),
    ddl         TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'PENDING',
    commit_id   BIGINT REFERENCES branch_commits(id),
    applied_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- No password column, by design - see TrackedDatabaseConfig.
CREATE TABLE IF NOT EXISTS tracked_databases (
    branch_name   TEXT PRIMARY KEY REFERENCES branch_metadata(branch_name),
    signature     TEXT NOT NULL,
    host          TEXT NOT NULL,
    port          INT NOT NULL,
    database_name TEXT NOT NULL,
    db_user       TEXT NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO branch_metadata (branch_name, forked_from) VALUES ('main', NULL)
    ON CONFLICT (branch_name) DO NOTHING;
