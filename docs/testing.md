# Testing

Three layers, each catching a class of bug the others structurally cannot.

| Layer | Size | Needs | Catches |
|---|---|---|---|
| Unit | 246 tests, 28 classes | nothing | **a wrong rule** — a DDL form accepted that shouldn't be, a conflict judged wrongly, a lock taken out of order |
| Integration | 61 tests, 10 classes | Docker | **a wrong result** — the model said one thing, the database it built says another |
| Shell scripts | 6 asserting scripts | a live daemon | **a wrong interaction** — real client processes, real wire protocol, real multi-process races |

## Running them

```bash
mvn test                            # unit only
mvn test -Dtest='*IntegrationTest'  # integration (needs Docker)

./dbService                         # in its own terminal, then:
./scripts/smoke-tests/concurrency-tests.sh
./scripts/smoke-tests/constraints-rejected-demo.sh
./scripts/smoke-tests/table-commands-rejected-demo.sh
```

Integration tests **skip rather than fail** without Docker (`MetadataStore.isDockerAvailable`). The
asserting scripts exit `1` on a failed assertion and `2` when the daemon is unreachable, so an absent
daemon is never misread as a failure.

## Unit — the rules

**The DDL acceptance matrix** is the largest block and reads as executable specification: one test per
accepted or rejected form, per dialect (`SqlDdlParserPostgresTest` 38, `MySqlTest` 9, `H2Test` 6). Each
dialect's parser is asserted strict to *its own* dialect, so a MySQL `MODIFY COLUMN` is rejected by a
Postgres-configured parser and vice versa. The tables in [`ddl-reference.md`](ddl-reference.md) and
these tests are the same list.

**The diff and replay engine** is where the subtle bugs live, and the test names read as the invariants
themselves. `DifferTest` (19) separates a difference from a disagreement, covering what only the
three-way model gets right (decision 7) — a one-sided change that is *not* a conflict, a rename racing a
modification that *is* one by stable id, and a side that undid its own change contributing nothing.
`SchemaOperationApplierTest` (20) and `ReplayerTest` (9) cover one operation each plus its error case,
including identity surviving a rename (decision 5). `CommitGraphTest` (7) pins ancestry over a shared graph;
`BranchLocksTest` (7) pins lock ordering and release-in-reverse (decision 8).

**Commands and wire** are split deliberately: `DbGitCommandsTest` (39) drives every verb and its error
cases without a socket, while `DbGitCommandListenerTest` (6) covers the socket path — so command
behaviour is tested without wire concerns, and framing (`SocketReaderTest`, `SocketWriterTest`,
`RequestHeaderTest`) without command concerns.

## Integration — the claim against the database

Each test assembles a whole dbgit installation: a daemon on its own port, a client with its own
`.dbgit` directory, a real socket, the real `MetadataVersioningService` over real jOOQ, real advisory
locks. Almost nothing is stubbed.

**Why this layer has to exist:** dbgit never introspects a database (decision 4) — the model comes only from
replaying recorded DDL. So none of the 246 unit tests can show that a fork, merge or reset actually
*put the schema it claimed into the database it built*. Only reading the database back can, and
`DatabaseSchema` does exactly that, querying `INFORMATION_SCHEMA` for tables, columns, types,
constraints and indexes. That readback is the only check on the seam between what dbgit believes and
what is true.

`MergeIntegrationTest` (13) doubles as documentation of merge semantics — a conflict refused *and
changing nothing*, both ways of resolving one (compensate, or `reset`), and merging back after a prior
merge bringing in only what is genuinely new. `RejectedDdlIntegrationTest` (17) is strict in a way
worth copying: for each refused form it asserts the command failed, failed **for the right reason**,
left nothing in the database, and left nothing staged. The concurrency tests race real sockets from
threads held at a barrier, covering the guarantees the locking model claims (decision 8).

**What stands in:** branch databases are in-memory H2, the metadata store a PostgreSQL testcontainer
(decision 9). Neither substitution touches the code under test — `H2Connections`/`H2Connector` are the same
production classes `dialect: "h2"` selects for real. *The one divergence:* H2 commits DDL implicitly, so
a part-way replay failure is not rolled back there as it would be on PostgreSQL; that behaviour is
asserted at the unit level instead (`BranchDatabaseRepositoryTest`).

## Shell scripts — what only separate processes prove

The four `concurrency-*-test.sh` scripts (run together by `concurrency-tests.sh`) cover isolation,
serialization, the commit race and recovery against a real `dbService` process and genuinely separate
client processes — the one thing the JVM-threaded integration tests cannot reproduce. They use unique
branch names per run and never touch `main`, so they need no `dbgit init`.
`constraints-rejected-demo.sh` and `table-commands-rejected-demo.sh` assert that every form that should
be refused still is, and create nothing.

## What is not tested

Deliberate rather than overlooked — [`decisions.md`](../decisions.md) §1 covers why.

- **No security tests** — there is no authentication, authorization or transport security to test.
- **No performance or load tests** — the pool's overflow path is asserted for behaviour (`ERR Server
  busy`), never for throughput.
- **No fault injection** — compensation is tested by making DDL fail, not by killing the daemon or
  partitioning the metadata store.
- **No CI** — nothing runs any of this automatically.

← [back to README](../README.md)
