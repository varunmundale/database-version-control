# dbgit

A git-like version control system for PostgreSQL (and MySQL/H2) schemas.

## Description

`dbgit` lets you treat a database schema the way git lets you treat source code. It's a
client/server system: a daemon (`dbService`) owns the branch graph and the databases behind it, and
a thin CLI client (`dbgit`) sends it commands.

- `dbgit checkout -b <branch>` forks a real, independent database seeded by replaying that branch's
  commit history.
- `dbgit add` (DDL piped via stdin) stages a DDL statement, validates it, and applies it to the
  branch's live database.
- `dbgit commit -m <message>` folds staged changes into the branch's history.
- `dbgit diff <a> <b>` compares two branches' schemas, table by table, column by column.
- `dbgit log` prints a branch's commits plus whatever is staged but uncommitted.
- `dbgit reset <commit>` rebuilds a branch's database from a truncated history.
- `dbgit merge <branch>` merges another branch in, failing on any conflicting change.

Nothing is introspected from a live database — the only way a table's shape becomes known to
`dbgit` is by parsing the DDL you ran through `dbgit add`, so what `dbgit diff`/`log`/`reset` see is
always exactly what was staged and committed. One branch, `main`, is special: instead of a
scratchpad fork, it tracks a real, pre-existing database you point it at with `dbgit init`.

Schema DDL is validated against whichever dialect you configure (`postgresql`, `mysql`, or `h2`) at
`dbgit add` time — a statement invalid for that dialect, or a form `dbgit` doesn't model (like an
inline column constraint), is rejected before it ever reaches a database.

## Recommended versions

Built and tested with:

| Tool                | Recommended     | Notes                                                                 |
|---------------------|-----------------|------------------------------------------------------------------------|
| Java                | 25 (LTS) or newer | `pom.xml` targets Java 25 language features; the build will not compile on an older JDK. |
| Maven               | 3.9.x or newer  | 3.8+ works; the project has no packaged jar, so `mvn` is how you build, test and run it. |
| Docker              | 24.x or newer   | Only needed for the `postgresql`/`mysql` branch-database dialects and the integration test suite (via Testcontainers); not needed if you run entirely on the `h2` dialect. |
| PostgreSQL (server) | 13+             | Required as `dbgit`'s own metadata store (bookkeeping tables), regardless of which dialect branch databases use. |

Everything else (jOOQ, JSqlParser, Jackson, the JDBC drivers, JUnit 5) is pinned in `pom.xml` and
fetched by Maven — nothing else needs to be installed by hand.

## Installation (from git)

```bash
git clone git@github.com:varunmundale/database-version-control.git
cd database-version-control
mvn compile
```

`dbgit` has no packaged-jar workflow — `./dbgit` and `./dbService` are thin wrappers around
`mvn exec:java`, so `mvn compile` (or just letting the first run of either script pull what it
needs) is all "installing" it means.

### Prerequisites before running it

1. **A metadata-store PostgreSQL server**, reachable and already running (e.g. `localhost:5432`).
   This is where `dbgit` keeps its own branch/commit/changeset bookkeeping — it is separate from any
   database your schemas actually live in. Configure it under `metadata` in
   `src/main/resources/dbgit.json`.
2. **Docker**, running and reachable via the `docker` CLI, if `branchDatabases.dialect` in
   `dbgit.json` is `postgresql` or `mysql` — a shared container is created/reused to host every
   branch's forked database. Not needed for the `h2` dialect, since an in-memory H2 database lives
   inside the daemon's own JVM.

(Optional) run the test suite to confirm everything is wired up correctly:

```bash
mvn test                          # unit tests only
mvn test -Dtest='*IntegrationTest' # + integration tests (needs Docker)
```

## Usage

`dbgit` is client/server: start the daemon once, then send it commands from any workspace directory.

### 1. Start the daemon

```bash
./dbService
```

Binds to the port configured in `dbgit.json` (`service.port`) and blocks, serving commands. Leave
it running in its own terminal.

### 2. Point `main` at a real database (once per workspace)

```bash
./dbgit init --host <host> [--port 5432] --database <database> --user <user> [--password <password>]
```

Idempotent — re-running it against the same target just refreshes the stored connection; pointing
it at a different target repoints `main` while keeping its commit history. Until this is run,
commands touching `main` will say so and refuse.

### 3. Everyday commands

Run these from the same working directory you ran `dbgit init` from — it holds `.dbgit/HEAD` (which
branch you're on) and `.dbgit/config.json` (how to reach `main`'s database).

```bash
# create and switch to a new branch, forked from the current one's database
./dbgit checkout -b feature/add-total-column

# stage a DDL statement (read from stdin, so it can span multiple lines)
echo "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);" | ./dbgit add

# fold staged changes into a new commit
./dbgit commit -m "add total column to orders"

# switch branches
./dbgit checkout main

# see what's changed between two branches
./dbgit diff main feature/add-total-column

# see a branch's commit history (plus anything staged but not committed)
./dbgit log

# list all branches; '*' marks the current one
./dbgit branch

# merge a branch in, failing on any conflicting change
./dbgit merge feature/add-total-column

# roll a branch back to an earlier commit, rebuilding its database
./dbgit reset 3

# full command reference
./dbgit help
./dbgit help <command>
```

### Command reference

| Command | Synopsis | What it does |
|---|---|---|
| `init` | `dbgit init --host <host> [--port 5432] --database <database> --user <user> [--password <password>]` | Points `main` at a real, already-existing database it tracks. |
| `checkout` | `dbgit checkout <branch>` / `dbgit checkout -b <branch>` | Switches branches, or forks the current branch's database into a new one. |
| `add` | `dbgit add <DDL statement (via stdin)>` | Stages one DDL statement and applies it to the current branch's live database. |
| `commit` | `dbgit commit [-m <message>]` | Folds the current branch's applied changesets into one new commit. |
| `branch` | `dbgit branch` | Lists every known branch. |
| `log` | `dbgit log` | Prints the current branch's commits, newest first, plus what's staged. |
| `reset` | `dbgit reset <commit>` | Takes the current branch back to a commit, rebuilding its database. Refused for `main`. |
| `diff` | `dbgit diff <branch1> <branch2>` | Compares two branches' schemas. |
| `merge` | `dbgit merge <branch>` | Merges another branch into the current one, failing on conflicts. |
| `help` | `dbgit help [<command>]` | Lists every command's usage, or describes one in detail. |

### Runnable walkthroughs

`scripts/` has end-to-end demo scripts you can run against a live daemon (from the repo root, e.g.
`./scripts/scratch_scripts.sh`) that exercise these workflows directly: basic branching, conflicting
and non-conflicting merges, constraints/indexes, `log`/`reset`, and (`concurrency-*-test.sh`) the
daemon's concurrency guarantees.
