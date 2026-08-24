# dbgit

**dbgit is Git for your database schema.**

Every branch is a real, independent database, forked in seconds and rebuilt from history whenever it
needs to be, not a diff you have to imagine. Point `main` at your
real production or staging database and everything else forks safely off it. Stage a change, commit
it, and `diff` shows you exactly what moved, right down to the column and constraint, even through a
rename. `merge` brings a branch back in the way git does — flagging a genuine conflict only when both
sides actually changed the same thing — and rehearses the whole merge on a throwaway database before
it ever touches yours. And because `dbgit` models the schema itself rather than just running whatever
you hand it, a statement it can't faithfully represent is rejected the moment you type it, not
discovered three merges later.

It has real multi-user support, not just single-laptop use: a whole team can work at once, with real
concurrency, and no one can corrupt another's branch. And it's multi-dialect: PostgreSQL, MySQL or
in-memory H2, same commands and same model either way.

## Try it!

**Live, in your browser — no install:** **http://34.44.41.11:8080/**

- **Initialize** points the shared `main` at whatever database you want it to track — it's a
  workspace setup step, not per-author, so the first person to run it sets it for everyone until it's
  changed.
- **Run script → clear-everything** wipes every branch, commit and forked database back to a blank
  slate. Use it to reset the instance if it gets messy — rest of the page is self-serve.

**Or locally, once `dbService` is running** (see [Install and run](#install-and-run) below):

```bash
./dbgit checkout -b add-invoices                     # a real, private database, forked in seconds
echo "CREATE TABLE invoices (id SERIAL, total NUMERIC(10,2));" | ./dbgit add
./dbgit commit -m "invoices table"
./dbgit diff main add-invoices                       # what changed, column by column
./dbgit merge add-invoices                           # bring it back, or be told exactly why not
```

## How this compares

Liquibase and Flyway apply an ordered list of scripts and hope everyone ran them in the same order;
Dolt versions your actual data, by replacing your database engine with its own. `dbgit` versions
*schema*, the way git versions code — branches, diffs, real three-way merges — while your data stays
in the PostgreSQL/MySQL you already run.

| | Liquibase / Flyway | Dolt | dbgit |
|---|---|---|---|
| Unit of version control | An ordered migration script, tracked in a table | Every row | Schema DDL |
| Branching | None — divergence is a human problem, resolved in the scripts themselves | A branch is data, in Dolt's own storage engine | A branch is a real, forked database |
| Merging | None | Row-level, built in | Column/constraint/index-level, three-way judged, rehearsed before it touches anything real |
| Your database | Runs scripts against it; doesn't model what's inside | *Is* the database — you migrate onto Dolt's engine | Runs alongside your existing PostgreSQL/MySQL/H2, unmodified |

### Under the hood

- **History is the only source of truth.** `dbgit` never introspects a database — fork, merge and
  `reset` all reduce to "replay this history," so there's no drift to reconcile.
- **Every table and column has a stable identity that survives a rename.** `RENAME COLUMN` reads as
  one column that moved, not a drop and an add — its indexes and constraints follow automatically.
- **Conflicts are judged three ways.** Two branches describing something differently isn't
  automatically a conflict — only if both diverged from what they last shared. `dbgit` shows you
  exactly which column, constraint or index, with both sides' statements side by side.
- **A merge is rehearsed before it's real.** Every merge replays into a throwaway staging database
  first; only a successful rehearsal ever touches your branch.
- **Branch mutations are lock-serialized.** A fixed lock order means even two merges going opposite
  directions can't deadlock or corrupt anything.

## How it works

One daemon (`dbService`) serves every client — the CLI and a browser, via a thin HTTP relay — over a
wire protocol that carries the caller's identity and branch on every request, so the server itself
holds no per-user state. Every mutating command produces two effects that have to agree: an in-memory
replay (what `add` validates against, what `diff`/`merge` compare) and a real write, to `dbgit`'s own
metadata store and to the branch's actual database.

Diagrams and a full request-by-request walkthrough: [`docs/architecture.md`](docs/architecture.md).

## What's supported

`dbgit` models a schema itself, so it accepts a deliberately small, precisely defined set of DDL —
`CREATE`/`DROP`/`RENAME TABLE`, column add/drop/rename/retype, constraints, indexes — against
PostgreSQL, MySQL or in-memory H2 branch databases, same commands and same model either way. Anything
it can't faithfully model (an inline `PRIMARY KEY`, `CHECK`, conditional DDL, ...) is refused at
`dbgit add` time with the statement to write instead, rather than silently accepted and invisible to
every later diff.

Full accepted/rejected statement tables, dialect notes and what's easy vs. hard to extend:
[`docs/ddl-reference.md`](docs/ddl-reference.md).

## Requirements

| Tool | Version | Needed for |
|---|---|---|
| Java | 25 (LTS) or newer | Building and running everything (`pom.xml` targets Java 25). |
| Maven | 3.9.x or newer | The build — there's no packaged jar; `mvn` is how you build, test and run. |
| Docker | 24.x or newer | `postgresql`/`mysql` branch-database dialects and integration tests. Not needed on `h2`. |
| PostgreSQL | 13+ | `dbgit`'s own metadata store, regardless of which dialect branch databases use. |

Everything else (jOOQ, JSqlParser, Jackson, JDBC drivers, JUnit 5) is pinned in `pom.xml`.

## Install and run

```bash
git clone git@github.com:varunmundale/database-version-control.git
cd database-version-control
mvn compile                 # or just let the first run of ./dbgit / ./dbService pull what they need

./dbService                 # start the daemon; leave it running in its own terminal

# in another terminal, once per workspace: point 'main' at a real, already-existing database
./dbgit init --host <host> --port <port> --database <database> --user <user> --password <password> --author "<your name>"

./dbgit checkout -b add-invoices
echo "ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);" | ./dbgit add
./dbgit commit -m "add total column"
./dbgit diff main add-invoices
./dbgit merge add-invoices

./dbgit help                # full command reference, straight from the code
```

A PostgreSQL server for the metadata store must already be reachable (configured under `metadata` in
`src/main/resources/dbgit.json`); Docker must be running if `branchDatabases.dialect` is `postgresql`
or `mysql`. `mvn test` runs unit tests; `mvn test -Dtest='*IntegrationTest'` adds integration tests
(needs Docker).

`scripts/smoke-tests/` has end-to-end scripts you can run against a live daemon; `scripts/live-demo-eof.sh`
is a full story — two branches diverging, a merge, a conflict resolved two ways — meant to be watched
rather than checked.

## Deploying it as a service

The hosted instance linked in [Try it!](#try-it) above runs this way — a browser client
(`scripts/deploy/web/index.html`) served by `relay.py`, a thin HTTP-to-TCP bridge in front of the same
daemon and wire protocol described in [`docs/architecture.md`](docs/architecture.md), behind two
systemd services. To run your own instance:

```bash
curl -fsSL https://raw.githubusercontent.com/varunmundale/database-version-control/master/scripts/deploy/bootstrap.sh | bash
```

One command: installs Docker, Postgres, a JDK, builds the project, and runs `dbService` plus a browser
UI behind two systemd services. Details and redeploy/teardown: [`docs/deploying.md`](docs/deploying.md).

## License

Evaluation only — see [`LICENSE`](LICENSE). No commercial use without permission from the copyright
holder; contact varunmundale@gmail.com to discuss one.
