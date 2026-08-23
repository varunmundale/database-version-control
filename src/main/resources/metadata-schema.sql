-- The metadata store's tables. Applied on first use by MetadataSchema, and safe to re-apply:
-- every statement is idempotent. Statements are separated by semicolons at end of line.

CREATE TABLE IF NOT EXISTS branch_commits (
    id                      BIGSERIAL PRIMARY KEY,
    parent_commit_id        BIGINT REFERENCES branch_commits(id),
    second_parent_commit_id BIGINT REFERENCES branch_commits(id),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Metadata databases created before merge commits existed have no second parent column.
ALTER TABLE branch_commits
    ADD COLUMN IF NOT EXISTS second_parent_commit_id BIGINT REFERENCES branch_commits(id);

-- Likewise for commits created before dbgit recorded who wrote them and why; both read back defaulted.
ALTER TABLE branch_commits ADD COLUMN IF NOT EXISTS author TEXT;
ALTER TABLE branch_commits ADD COLUMN IF NOT EXISTS message TEXT;

-- The branch a commit was created on, so a log can say where each commit in a branch's inherited history actually
-- came from. Deliberately not a foreign key: the commit graph is shared and outlives branches (a merge's staging
-- branch is dropped as soon as the merge settles), and a commit must not keep a finished branch alive. Commits
-- created before this column existed read back as unknown.
ALTER TABLE branch_commits ADD COLUMN IF NOT EXISTS branch_name TEXT;

-- A commit can have several children - that is what branching is - so a single forward pointer could never be
-- right. Nothing read it (ancestry walks parent_commit_id), while two branches sharing a HEAD both wrote it,
-- holding locks on different branches. Children are derivable from the parent columns if ever needed.
ALTER TABLE branch_commits DROP COLUMN IF EXISTS next_commit_id;

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
