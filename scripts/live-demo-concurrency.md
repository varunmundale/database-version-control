# The concurrency live demo

`scripts/live-demo-concurrency.sh` prepares two loaded branches and prints a card of commands. This
file is the other half: what each act proves, why it is written the way it is, and what to say while
it runs. The script prints none of this - it is meant to be read once, before the demo.

## Setup

```
./dbService                             # in its own terminal, left running
./scripts/live-demo-concurrency.sh      # ~1 minute, mostly loading rows
```

It creates two branches - `lockdemo` (table `events`) and `lockdemo-other` (table `readings`) - each
with twelve million rows loaded straight into its forked database with `psql`, since dbgit versions
schema, not data.

**Why real rows.** dbgit is faster than a person: a branch is held for the few milliseconds a command
takes, so two commands typed by hand can never collide. Nothing in the demo fakes a lock or sleeps.
Twelve million rows make a `CREATE INDEX` take real time and hold its branch for as long as
PostgreSQL needs, which is the case branch locks exist for. `ROWS=…` turns it up if you talk slowly;
budget for act 2 building two indexes back to back.

**The state file.** The script writes `.live-demo.tmp` (gitignored) with `BRANCH_A`, `BRANCH_B`,
`WORKSPACE_B`, `DB_A`, `DB_B` and the scratchpad container's name, and every terminal sources it. A
terminal opened after the script finished inherits none of the script's variables, and a demo typed
into two fresh terminals is the whole point - so the acts read them by name at the moment they run.
`DBGIT_WORKSPACE` is deliberately *not* in the file: terminal 1 must not have it set, and that one
variable is the entire difference between the two callers.

**Two terminals, one workspace or two.** Acts 1-3 want both terminals on the same branch, which is
what running them from the same directory does - the branch lives in `.dbgit/HEAD`, not in the
daemon. Acts 4 and 5 want terminal 2 to be a different caller on a different branch, which is
`export DBGIT_WORKSPACE="$WORKSPACE_B"` and no second checkout of the repo.

**Why every act indexes something new.** dbgit has no way to drop an index. `DROP INDEX idx` is
refused - an index name carries no table, and dbgit rebuilds a schema by replaying history one table
at a time, so it cannot tell which table's model to edit - and the MySQL-style
`ALTER TABLE t DROP INDEX idx` is not accepted either. The modelled way to remove one is to drop the
constraint that owns it. So each act indexes a different column list: same cost, no cleanup.

**Timing.** A second or two of every `real` is Maven starting the client JVM. It is the same overhead
in every act, so the comparisons hold.

## Act 1 - five commands race for the same column

Five `dbgit add`s of the same `ALTER TABLE` at once. One reports `Applied changeset #N`; the other
four say `Column already exists: contended`.

The four losers are the interesting part: each is seeing what the winner did. That is only possible
if they went through the branch one at a time - had they validated against the same past, all five
would have succeeded and the column would have been added five times. `dbgit log` afterwards shows
one `contended` column in the working set, not five.

`Stager` holds the branch lock from its first read to its last write, so validation, the real DDL and
the row recording it cannot interleave with anyone else's.

## Act 2 - a migration holds the branch, the next command waits

Two index builds on `events`, issued back to back from two terminals. Terminal 2 hangs, and only
starts building once terminal 1 has returned: its `real` is both builds end to end, terminal 1's is
one.

Nothing about the second index needs the first. PostgreSQL would happily build them side by side -
act 4 shows it doing exactly that. What terminal 2 waited for is the branch.

The lock is deliberately **session**-scoped rather than transaction-scoped: staging the changeset,
running the DDL and marking it applied are three transactions with an irreversible statement in the
middle, so a `pg_advisory_xact_lock` would already have been released by the time the real DDL ran.

## Act 3 - reads never wait

While terminal 1 builds an index, terminal 2 runs `log`, `branch` and `diff`. Each returns in the
time it takes to start the client.

`log`, `diff` and `branch` only read history, so they take **no** lock at all - a migration that
holds a branch for half a minute never blocks anyone looking at it.

Two things worth pointing at in the output:

- The log shows the in-flight statement as `[PENDING]`, not `[APPLIED]`. The changeset row is written
  before the DDL runs and flipped to `APPLIED` after it comes back, so a reader sees the migration in
  flight, honestly labelled, rather than a lie in either direction. Run the same log after terminal 1
  returns and the same changeset reads `APPLIED`. This is not a dirty read: that row was committed by
  its own transaction, and `PENDING` is exactly what the status column exists to say.
- Each read runs inside one `REPEATABLE READ` snapshot (`MetadataDatabase.snapshot`). Reconstructing
  a branch's history takes three queries, and outside a transaction jOOQ takes a fresh connection per
  query - so without the snapshot a commit landing halfway through could be seen by one query and not
  the next.

**"Shouldn't a writer block a reader?"** Only for read-modify-write, and those reads are already
inside the lock: `Stager`, `Committer`, `Merger` and `Resetter` all acquire the branch *first* and
read *after*. What takes no lock is only the reads whose output goes to a human, and locking those
would buy nothing - `dbgit log` is stale the moment it reaches your terminal anyway. The backstop is
`BranchMetadataRepository.updateHeadCommitId`, a compare-and-set: a decision made on a stale read
fails loudly instead of silently orphaning a commit.

## Act 4 - two branches migrate at the same time

Act 2's experiment with exactly one thing changed - the second index is built on the other branch -
and the opposite result: `real` comes out far closer to one build than to two, where act 2's two
added up.

The two builds compete only for the disk and the CPU, never for a lock. Locks are per branch, and the
daemon runs commands on a bounded pool (`concurrency.handlerThreads` in `dbgit.json`, 8 by default),
so both were genuinely inside PostgreSQL at once.

The two `dbgit log`s afterwards show each branch holding only its own index. Two callers, one daemon,
no shared state: the daemon holds no working directory and no HEAD of its own - each request carries
the caller's branch in its `DBGIT/1` header.

## Act 5 - a merge needs both branches, so either one can block it

Every act so far has taken one branch. A merge is the other case: `Merger` acquires the branch it is
run on *and* the branch it is merging, and holds both for the whole operation - the conflict check,
the replay into two real databases, and the merge commit. Otherwise those three are views of a
history that can move underneath them, and a merge commit whose parents were never the basis for
what was replayed is corruption no later read can detect.

Both branches are committed first, because a merge compares committed histories - each side needs
something committed the other lacks.

**The problem this act solves.** The second lock is the hard one to show. A merge that succeeds looks
exactly the same whether it took one lock or two, and with these two branches a merge is over in
milliseconds anyway: everything it replays into the other side lands in a table it has just created
empty, so there is nothing slow about it. So the demo runs the other way round - **hold the branch
first, and let the merge be the thing that waits.**

### Part 1 - a write on `lockdemo-other` blocks a merge run on `lockdemo`

Terminal 2 starts a twenty-second index build on `lockdemo-other`. Terminal 1, straight after, runs
`dbgit merge lockdemo-other` from `lockdemo`.

`lockdemo` is completely idle, and it is the branch the merge is being run on. Yet the merge's `real`
is terminal 2's entire build. The only branch it was waiting for is the one it merely named as an
argument - which is the second lock, made visible from outside.

### Part 2 - the mirror

The same thing with the two branches swapped: terminal 1 holds `lockdemo` with a build, terminal 2
runs `dbgit merge lockdemo` from `lockdemo-other`. Same result. The block is not about which branch is
"the target" - a merge simply needs both.

### Blocked, not refused

`AdvisoryBranchLock` is `pg_try_advisory_lock` in a poll loop, not a blocking `pg_advisory_lock`, so
a merge that cannot get a branch retries every 100ms until `concurrency.lockTimeoutMs` (60s in
`dbgit.json`) and only then gives up. At the default `ROWS`, a build finishes long before that, so
what you see is waiting.

To see it give up instead, drop `lockTimeoutMs` to `5000`, restart `./dbService`, and run part 1
again:

```
Branch 'lockdemo-other' is busy: another command has been holding it for more than 5s. Try again shortly.
```

An error naming the **other** branch, raised by a command run on this one. That is the same fact as
part 1, stated by the tool itself rather than inferred from a stopwatch.

### Afterwards, on either branch

- `dbgit branch` shows no `merge/…` branch left behind. Each merge forks one to prove the replay
  works before touching a real database, names it per attempt (`merge/<a>-<b>-<nonce>`) so two merges
  of the same pair cannot collide, and drops it in a `finally` either way.
- The merge commit carries `Changesets: (none)`. What it brought in stays attributed to the commits
  that originally introduced it, reachable by walking the second parent. The `Branch:` line is what
  tells a branch's own commits from the ones it merely inherited.

### What this act deliberately does not show

**Lock ordering.** `BranchLocks.acquire` sorts the branches lexicographically whichever way round the
merge was asked for, so two merges in opposite directions cannot sit holding one apiece. That is a
real guarantee, but it is not demoable: a run that works looks identical to a run with no ordering at
all, because with these branches the contended window is milliseconds wide and a broken
implementation would get away with it nearly every time. It is checked instead by
`scripts/smoke-tests/concurrency-tests.sh` and by the `org.example.integration.concurrency` tests,
where the race can be forced rather than hoped for.


## Afterwards

`./scripts/clear-everything.sh -y` puts dbgit back to a first run and reclaims the several GB the two
branches hold. To keep the rest of your dbgit state, drop just the two databases and remove
`$WORKSPACE_B` and `.live-demo.tmp` - the card prints both forms.

## What this demo is not

Nothing here asserts anything; it is meant to be watched. The same guarantees are checked
automatically, without a daemon or a stopwatch, by `scripts/smoke-tests/concurrency-tests.sh` (real
client, real wire protocol, real processes) and by the `org.example.integration.concurrency` tests
(same guarantees as threads in one JVM).
