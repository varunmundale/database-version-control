# DDL and dialect reference

## SQL statements `dbgit add` accepts

`dbgit` models a schema itself, so it accepts a deliberately small, precisely defined set of DDL.
Everything below is parsed into the internal model, replayed on every fork, and understood by `diff`
and `merge`. Anything not on this list is rejected at `dbgit add` time, before it reaches a database.

| Statement | PostgreSQL / H2 | MySQL |
|---|---|---|
| Create a table (columns only) | `CREATE TABLE t (c1 …, c2 …)` | same |
| Drop a table | `DROP TABLE t` | same |
| Rename a table | `ALTER TABLE t RENAME TO u` | same — MySQL's own `RENAME TABLE t TO u` is not accepted; write it as `ALTER TABLE` |
| Add a column | `ALTER TABLE t ADD COLUMN c <type> …` | same |
| Drop a column | `ALTER TABLE t DROP COLUMN c` | same |
| Rename a column | `ALTER TABLE t RENAME COLUMN a TO b` | same |
| Change a column's type | `ALTER TABLE t ALTER COLUMN c TYPE <type>` (a trailing `USING <expr>` is accepted and ignored) | `ALTER TABLE t MODIFY COLUMN c <type>` |
| Add a constraint | `ALTER TABLE t ADD CONSTRAINT n PRIMARY KEY (…)` / `UNIQUE (…)` / `FOREIGN KEY (…) REFERENCES …` | same |
| Drop a constraint | `ALTER TABLE t DROP CONSTRAINT n` | same |
| Create an index | `CREATE [UNIQUE] INDEX n ON t (c1, c2)` | same |

A table rename is **tracked as a rename**, not read back as a drop plus an add: the table keeps its
identity across the statement, so `dbgit diff` shows one table whose name differs, and every column,
constraint and index it carries survives untouched. `dbgit merge` can therefore bring in a one-sided
rename cleanly, and reports a genuine conflict — both branches renamed the same table — as a
table-level conflict, the same way it already does for a column.

Inside a column definition, `NOT NULL`, `DEFAULT <value>` and the dialect's identity spelling
(`GENERATED ALWAYS/BY DEFAULT AS IDENTITY` on PostgreSQL, `AUTO_INCREMENT` on MySQL, either on H2)
are all understood and tracked. `SERIAL` and friends are just types, and pass through as written.

Each dialect's parser is **strict to its own dialect**: a MySQL `MODIFY COLUMN` on a PostgreSQL
branch is rejected when you type it, rather than accepted and left to fail later during a fork or a
merge.

## What is deliberately rejected, and why

| Rejected | Reason |
|---|---|
| A constraint declared inside `CREATE TABLE` — inline (`id INT PRIMARY KEY`, `email TEXT UNIQUE`, `… REFERENCES other(id)`) or table-level (`, PRIMARY KEY (id)`) | These used to be silently ignored: the constraint existed in the real database but not in the model, invisible to `diff` and missing from every later fork. The error names the `ALTER TABLE … ADD CONSTRAINT` to write instead. |
| `CHECK` constraints | Not modelled — there is nowhere in `ConstraintType` for them to live. |
| `DROP INDEX` (in any spelling) | An index name carries no table, and `dbgit` rebuilds a schema by replaying history one table at a time, so it cannot tell whose index it was. Drop the constraint that owns it instead. |
| `IF EXISTS` / `IF NOT EXISTS`, on any statement | A statement must mean the same thing every time it is replayed. A conditional clause means one thing on a branch that has the object and nothing on one that does not. |
| `DROP TABLE … CASCADE` | Can silently drop constraints on *other* tables that replay never sees and `dbgit diff` can never report. Drop the referencing constraint first. |
| `DROP TABLE` naming several tables at once, or a `TEMPORARY` table | Not modelled. |
| Index types other than plain and `UNIQUE` | Not modelled. |
| Several changes in one `ALTER TABLE` (`ADD a INT, ADD b INT`) | One statement, one change — that is the unit a changeset, a diff and a conflict are all expressed in. Write them as two. |
| `RENAME TABLE t TO u` (MySQL's own spelling) | `ALTER TABLE t RENAME TO u` covers every dialect, so only that form is accepted. |
| Views, sequences, triggers, functions, stored procedures, schemas other than `public` | Not modelled yet. See "extending" below. |
| Any DML (`INSERT`, `UPDATE`, `DELETE`), and data generally | Out of scope: `dbgit` versions schema. Load data into a branch's database directly — it is a normal database. |

## Databases

| Role | Supported | Notes |
|---|---|---|
| **Branch databases** (what your schema actually lives in) | PostgreSQL, MySQL, H2 | Set `branchDatabases.dialect` in `dbgit.json`. PostgreSQL and MySQL run in a shared Docker container `dbgit` manages; H2 is in-memory inside the daemon and needs no Docker at all. |
| **The database `main` tracks** | Same dialect as above | A real, pre-existing database you point at with `dbgit init`. |
| **`dbgit`'s own metadata store** | PostgreSQL only | Not configurable. It uses jOOQ pinned to the Postgres dialect, Postgres DDL, and Postgres advisory locks for branch locking. |

## Easy to extend

These are the seams the design already has. Each is a small, local change:

- **Another SQL dialect.** Two halves, registered together in `ConnectorRegistry`: a `JdbcConnections`
  that builds that vendor's URL, and a `SqlConnector` that runs raw SQL. Then a `DialectGrammar` value
  — how it spells a retype and what its identity columns look like — registered in
  `DdlParserRegistry`. Add a `ContainerSpec` if branch databases for it should run in Docker; omit it
  (as H2 does) if they should not. No existing logic changes.
- **Another DDL statement that edits one table.** Add a `SchemaOperation` variant, a branch in
  `SqlDdlParser`, and the matching edit in `SchemaOperationApplier`. Everything downstream — replay,
  fork, diff, merge, reset — picks it up for free, because they all work in terms of the model rather
  than the SQL. `DROP TABLE`/`RENAME TO` are the one exception worth knowing about: they're the first
  two operations whose effect is on the schema as a whole rather than on a single table, so they also
  needed `Replayer.apply(Map, String)` (to move the map key) and `DatabaseDiff` matching tables by
  stable id (to make a rename read as a rename) rather than by name.
- **`CHECK` constraints.** A `ConstraintType` value plus somewhere on `ConstraintModel` to keep the
  expression.
- **Constraint or index renames tracked as renames.** Tables and columns already carry a stable
  identity through a rename; a constraint's or index's own identity is still just its name, so
  renaming one reads as a drop plus an add. Giving them the same treatment is the same mechanism
  applied once more.
- **Another command.** One `Command` subclass and one line in `CommandFactory`. Commands run against
  a `CommandContext` rebuilt per request, so nothing else needs to know.
- **Different concurrency limits.** Thread count, queue depth, socket, drain and lock timeouts are all
  in `dbgit.json` under `concurrency`.

## Harder — these need real design first

- **Versioning data alongside schema.** Row-level conflicts are a different problem.
- **Replaying a merge against a renamed table or column.** A merge replays the incoming branch's raw
  DDL text, so a statement naming a table or column the target has since renamed will not apply — it
  fails in the merge's staging branch, before the target's own database is touched, so nothing is
  corrupted, but the merge still has to be resolved by hand. Resolvable today by compensating on the
  renaming side; a real fix means replaying resolved operations rather than text.
- **A metadata store that is not PostgreSQL**, or one that is not a single point of failure.
- **Very long histories.** A fork replays the whole history, so fork time grows with it. The answer is
  snapshot/compaction commits, not introspection.
- **A foreign key's target resolved by id rather than by name.** `SchemaOperationApplier` resolves an
  FK's referenced table from the name written in the DDL, not from a lookup — cheap, and correct for
  every FK added before its target is ever renamed, but one added after a rename won't derive the
  target's real id.

Why the accepted set is a strict whitelist, and why anything unmodellable is refused rather than
silently ignored, is argued as decision 10 in [`decisions.md`](../decisions.md); the dialect-as-data split
behind the per-vendor differences above is decision 1.

← [back to README](../README.md)
