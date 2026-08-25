# Code map

Where things live, and where to start reading. For what the system *does* at runtime, see
[`docs/architecture.md`](architecture.md); for why it is shaped this way,
[`decisions.md`](../decisions.md).

Everything is under `org.example`. The layering runs in one direction:

```
client  →  service  →  core  →  repository  →  connectors
                        ↕
                     adapters, models
```

`client` talks to `service` over a socket; `service` picks a `Command`; the command calls one `core`
operation; that operation reads and writes through `repository`; every JDBC connection in the
codebase is opened by `connectors`. `adapters` and `models` sit alongside rather than below — they
are the schema representation and the DDL parser that builds it, used mostly by `core`.

**The one orienting fact:** every mutating command produces *two* effects that have to agree. An
in-memory replay — `Replayer` parsing recorded DDL into `TableModel`s, which is what validates
`dbgit add` and what `diff`/`merge` compare — and a real write, split between the metadata store
(bookkeeping rows) and the branch's actual database (real DDL). A branch database is never read back
into the model; the model only ever comes from replaying recorded DDL.

## Packages

| Package | What it owns | Entry point |
|---|---|---|
| `client` | The `dbgit` process: opens a socket, writes one command line (plus a DDL body for `add`), streams the response back. Also owns `.dbgit/` — the `HEAD` file naming your branch and `config.json` holding your connection and author. Deliberately client-side, so one user's `checkout` can't move another's HEAD. | `Main`, `DbGitClient`, `ClientWorkspace` |
| `service` | The daemon. `DbGitCommandListener` binds the `ServerSocket`, owns the accept loop, and hands each connection to a bounded pool. `ConnectionHandler` frames one connection into a request — header line, command line, then a body for `add` alone. `SocketReader`/`SocketWriter` are the only places the wire bytes and the `OK`/`ERR` format are handled. | `DbServiceMain`, `DbGitCommandListener` |
| `service.command` | One class per `dbgit` verb (`AddCommand`, `CommitCommand`, `DiffCommand`, `MergeCommand`, `ResetCommand`, …). `CommandFactory` maps arguments to the right one. `CommandContext` splits shared collaborators (built once) from the per-request `RequestContext` — that split is what makes the daemon multi-user. | `CommandFactory`, `Command` |
| `core.forker` | Materializes a branch's database: creates it in the shared scratchpad and replays every committed statement into it. `BranchConnections` decides *which* database a branch writes to — `main`'s tracked one, or a fork. Sub-package `docker` keeps the shared container up. | `Forker` |
| `core.stager` | `dbgit add`: previews the statement in memory, stages it `PENDING`, runs it for real, marks it `APPLIED`. | `Stager` |
| `core.committer` | Folds a branch's `APPLIED` changesets into one commit and moves HEAD. | `Committer` |
| `core.merger` | Merges a diverged branch in, using the same `Differ` that `dbgit diff` uses. Rehearses on a throwaway staging branch before touching anything real. | `Merger` |
| `core.differ` | Compares two branches. Divergence is a set difference by commit id; differences are judged against a *third* schema — the history both branches share — which is what separates a genuine conflict from a change one side hasn't received. | `Differ` |
| `core.replayer` | Rebuilds a schema in memory, no database: parses each changeset's DDL into a `SchemaOperation` and folds it into a `TableModel`. | `Replayer`, `SchemaOperationApplier` |
| `core.resetter` | Takes a branch back to a commit by truncating history, discarding the working set, and rebuilding the database. | `Resetter` |
| `core.log` | What `dbgit log` has to say about a branch: its commits, plus what is staged but uncommitted. | `BranchLog` |
| `core.versioning` | The versioning API the rest of dbgit works against — which branches exist, and how each one's history is reconstructed from the shared commit graph. Callers only ever see the interface. | `VersioningService`, `MetadataVersioningService` |
| `core.locking` | Per-branch locks, taken in lexicographic order and released in reverse so two opposite merges can't deadlock. | `BranchLocks` |
| `repository` | Every database dbgit persists to, one repository per thing stored. Two groups: `BranchMetadataRepository`/`CommitRepository`/`ChangesetRepository`/`TrackedDatabaseRepository` are jOOQ access to the metadata store (with `MetadataDatabase` owning the jOOQ boundary), and `BranchDatabaseRepository` is the scratchpad where branch databases are created and DDL actually runs. Failures surface as unchecked `RepositoryException`. | `MetadataDatabase`, `BranchDatabaseRepository` |
| `connectors` | JDBC plumbing — the only place a JDBC URL is built or a connection opened. Vendor support comes in two halves per dialect: a `JdbcConnections` that builds the URL and opens a `Connection`, and a `SqlConnector` that wraps one to run raw SQL. Sub-packages `postgres`, `mysql`, `h2`. | `ConnectorRegistry` |
| `adapters` | Turns vendor DDL text into the model, and nothing else creates a `TableModel`. `SqlDdlParser` (behind the `DdlParser` interface) dispatches to `ColumnMapper`, `ColumnSpecs`, `ConstraintMapper` and `SqlIdentifiers`. Per-dialect differences are a `DialectGrammar` *value* injected into the constructor, not a subclass. | `SqlDdlParser`, `DdlParserRegistry` |
| `models.schema` | The internal schema representation: `TableModel`, `ColumnModel`, `ConstraintModel`, `IndexModel`. `StableId` gives tables and columns an identity that survives a rename, which is what lets a diff match them across branches. | `TableModel`, `StableId` |
| `models.versioning` | The stored vocabulary: `ChangeSet`, `ChangesetStatus` (`PENDING` → `APPLIED` → `COMMIT`), `Commit`, `CommitEntry`, `CommitParents`, `CommitMetadata`. | `ChangeSet`, `Commit` |
| `config` | Loads `dbgit.json` once and hands out typed sections — the branch-database dialect, the metadata store, the service port, concurrency limits. | `DbGitConfig` |
| `request` | The wire contract: who is asking, which branch they are on, and how to reach the database `main` tracks. `RequestHeader` is the only place that format is read or written. | `RequestContext`, `RequestHeader` |

## Start here

Roughly an hour, in this order:

1. **`service/DbGitCommandListener`** — the daemon in one file: bind, accept, hand to the pool.
   Note that `execute`/`add` are public and socket-free, which is how the tests drive every verb
   without a client.
2. **`service/command/CommandContext`** — the shared-vs-per-request split. Understand this and the
   multi-user story falls out; miss it and nothing else makes sense.
3. **`service/command/AddCommand` → `core/stager/Stager`** — the shortest path from a socket to
   both effects. `stage` takes the branch lock and delegates; the twenty lines of `staged` beneath it
   show the whole pattern: preview in memory, stage the row, execute for real, mark applied,
   compensate on failure.
4. **`core/replayer/Replayer`** — how a history becomes a schema. The other half of every operation.
5. **`core/differ/Differ`** — the three-schema comparison. The most subtle code in the repo, and
   worth reading with [`decisions.md`](../decisions.md) decision 7 open beside it.
6. **`core/versioning/MetadataVersioningService`** — how a branch's history is reconstructed from a
   commit graph shared by every branch.
7. **`core/locking/BranchLocks`** — short, and explains why concurrent work is safe.

## Where would I add…

Pointer-level; [`docs/ddl-reference.md`](ddl-reference.md) has the full "Easy to extend" section.

- **A new command** — one `Command` subclass in `service.command` declaring its own
  `public static final CommandUsage USAGE`, and one line in `CommandFactory`. `dbgit help` reads that
  same constant, so it cannot drift from what the command actually accepts. Commands run against a
  `CommandContext` rebuilt per request, so nothing else needs to know.
- **A new DDL statement** — a `SchemaOperation` variant, a branch in `SqlDdlParser`, and the
  matching edit in `SchemaOperationApplier`. Replay, fork, diff, merge and reset pick it up for
  free, because they all work in terms of the model rather than the SQL.
- **A new dialect** — a `JdbcConnections` and a `SqlConnector`, registered together in
  `ConnectorRegistry`; then a `DialectGrammar` value registered in `DdlParserRegistry`. Add a
  `ContainerSpec` if its branch databases need Docker, omit it (as H2 does) if they don't.
- **A new concurrency limit** — `ConcurrencyConfig`, read from `dbgit.json`.

← [back to README](../README.md)
