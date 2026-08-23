#!/usr/bin/env bash
# Sets up a branch-lock demo, then prints the commands to type in two terminals. Five acts:
#
#   1. five commands race for the same column on one branch - one wins  (serialization)
#   2. a migration holds the branch, the next command on it waits       (the lock's scope)
#   3. reads never wait, not even for a migration in flight             (reads take no lock)
#   4. two branches migrate at the same time, in the time of one        (the lock is per branch)
#   5. a merge is blocked by work on either branch it needs            (merge locks both)
#
# What each act proves, why it is written the way it is, and what to say while it runs:
# scripts/live-demo-concurrency.md. The card this prints is the commands only.
#
# Requires ./dbService running and Docker up. Nothing here fakes a lock: two branches each get a
# table with twelve million rows, so a real CREATE INDEX takes real time and holds its branch for as
# long as PostgreSQL needs. The rows go in with psql - dbgit versions schema, not data.
set -euo pipefail
cd "$(dirname "$0")/.."

BRANCH_A="${BRANCH_A:-lockdemo}"
BRANCH_B="${BRANCH_B:-lockdemo-other}"
ROWS="${ROWS:-12000000}"             # ~15s to load per branch, and gives you a ~20s window per index
                                     # build - act 2 builds two of them back to back, so budget for it.
                                     # Turn it up if you talk slowly; the builds scale with it.
WORKSPACE_B="${WORKSPACE_B:-.dbgit-tmp/dbgit-lockdemo-workspace-b}"
STATE_FILE="${STATE_FILE:-.live-demo.tmp}"   # what each terminal sources; gitignored
CONFIG="src/main/resources/dbgit.json"

command -v docker >/dev/null || { echo "docker is not on PATH, and the row load needs it." >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is not on PATH; it is what reads $CONFIG." >&2; exit 2; }

# The container and credentials come from dbgit.json rather than being written twice.
eval "$(python3 - "$CONFIG" <<'PY'
import json, shlex, sys
branches = json.load(open(sys.argv[1]))["branchDatabases"]
for name, value in {
    "BRANCH_CONTAINER": branches["containerName"],
    "BRANCH_USER": branches["user"],
    "HANDLER_THREADS": json.load(open(sys.argv[1])).get("concurrency", {}).get("handlerThreads", 8),
}.items():
    print(f"{name}={shlex.quote(str(value))}")
PY
)"

PSQL=(docker exec -i "$BRANCH_CONTAINER" psql -q -v ON_ERROR_STOP=1 -U "$BRANCH_USER")

# The scratchpad database a branch is forked into - BranchConnections.forkedDatabaseName, in bash.
forked_database() {
    local readable
    readable="$(printf '%s' "$1" | tr -c 'a-zA-Z0-9_.-' '_' | tr 'A-Z' 'a-z')"
    printf '%s_postgres' "${readable:0:40}"
}

DB_A="$(forked_database "$BRANCH_A")"
DB_B="$(forked_database "$BRANCH_B")"

rm -rf .dbgit/ "$WORKSPACE_B"
mkdir -p "$WORKSPACE_B"

./dbgit branch >/dev/null 2>&1 || { printf 'Start the daemon first:\n\n    ./dbService\n\n' >&2; exit 2; }

# workspace, branch, table, database - one prepared branch: forked, given a committed table, filled.
prepare() {
    local workspace="$1" branch="$2" table="$3" database="$4"
    echo "=== Preparing '$branch' ==="
    DBGIT_WORKSPACE="$workspace" ./dbgit checkout -b "$branch" || {
        printf "\nCould not create '%s'. If a previous run of this demo left it behind, clear dbgit and retry:\n\n    ./scripts/clear-everything.sh -y\n\n" "$branch" >&2
        exit 2
    }
    DBGIT_WORKSPACE="$workspace" ./dbgit add <<<"CREATE TABLE $table (id INT, payload TEXT);"
    DBGIT_WORKSPACE="$workspace" ./dbgit commit -m base table
    echo "Loading $ROWS rows into $database..."
    "${PSQL[@]}" -d "$database" -c "INSERT INTO $table SELECT g, md5(g::text) FROM generate_series(1, $ROWS) g;"
    echo "Loaded."
}

prepare "$PWD" "$BRANCH_A" events "$DB_A"
prepare "$WORKSPACE_B" "$BRANCH_B" readings "$DB_B"
source .live-demo.tmp

# Everything the acts need, in one file the demo's terminals source rather than inherit. A terminal
# opened after this script finished has none of these variables - and a demo typed into two fresh
# terminals is the whole point - so the acts below read them at the moment they run instead of being
# printed with the values baked in. Gitignored; delete it when the demo is over.
{
    printf '# Written by scripts/live-demo-concurrency.sh - source this in every terminal you run the demo from:\n'
    printf '#     source %s\n' "$STATE_FILE"
    printf 'export BRANCH_A=%q\n' "$BRANCH_A"
    printf 'export BRANCH_B=%q\n' "$BRANCH_B"
    printf 'export WORKSPACE_B=%q\n' "$WORKSPACE_B"
    printf 'export DB_A=%q\n' "$DB_A"
    printf 'export DB_B=%q\n' "$DB_B"
    printf 'export BRANCH_CONTAINER=%q\n' "$BRANCH_CONTAINER"
    printf 'export BRANCH_USER=%q\n' "$BRANCH_USER"
    printf 'export ROWS=%q\n' "$ROWS"
} > "$STATE_FILE"
# DBGIT_WORKSPACE is deliberately not in there: terminal 1 must not have it set, and terminal 2 sets
# it from WORKSPACE_B itself - that one variable is the whole difference between the two callers.

cat <<CARD

##############################################################################
#  Ready. Two branches, loaded: '$BRANCH_A' (events) and '$BRANCH_B' (readings).
#
#  In EVERY terminal you run an act from:
#
#      cd $PWD
#      source $STATE_FILE
#
#  Terminal 1 is then on '$BRANCH_A'. Terminal 2 is the same directory, so it is
#  on the same branch - until acts 4 and 5, which want it to be another caller:
#
#      export DBGIT_WORKSPACE="\$WORKSPACE_B"    # already on '$BRANCH_B'
#
#  What each act proves, and what to say while it runs:
#      scripts/live-demo-concurrency.md
##############################################################################

--- Act 1: five commands race for the same column ---------------------------
  One wins, four are told the column already exists - they are seeing the
  winner's work, so they went through the branch one at a time.

  for i in 1 2 3 4 5 ; do
      ./dbgit add <<< "ALTER TABLE events ADD COLUMN contended INT;" &
  done; wait

  ./dbgit log        # one contended column in the working set. Not five.

--- Act 2: a migration holds the branch, the next command waits -------------
  Two index builds, one branch. Terminal 2's 'real' is both of them end to
  end - it waited for the branch, not for PostgreSQL.

  # terminal 1
  time ./dbgit add <<< "CREATE INDEX events_payload_idx ON events (payload);"

  # terminal 2, straight after - don't wait for terminal 1
  time ./dbgit add <<< "CREATE INDEX events_id_idx ON events (id);"

--- Act 3: reads never wait -------------------------------------------------
  All three answer while the build is still running: reads take no lock. The
  log shows the in-flight statement as [PENDING], not APPLIED.

  # terminal 1
  time ./dbgit add <<< "CREATE INDEX events_payload_id_idx ON events (payload, id);"

  # terminal 2, while that is still building
  time ./dbgit log
  ./dbgit branch
  ./dbgit diff $BRANCH_A $BRANCH_B

--- Act 4: two branches migrate at the same time ----------------------------
  Act 2 again with one thing changed - the second index is on another branch -
  and 'real' is now one build, not two. Locks are per branch.

  time ( ./dbgit add <<< "CREATE INDEX events_id_payload_idx ON events (id, payload);" & \
         DBGIT_WORKSPACE=$WORKSPACE_B \
          ./dbgit add <<< "CREATE INDEX readings_payload_idx ON readings (payload);" & \
         wait )

  ./dbgit log                               # only $BRANCH_A's index
  DBGIT_WORKSPACE=$WORKSPACE_B ./dbgit log # only $BRANCH_B's

--- Act 5: a merge needs BOTH branches, so either one can block it ----------
  Every act so far took one branch. A merge is the other case: it holds the
  branch it is run on AND the branch it is merging, for the whole operation.
  So it can be held up by work on a branch it is not even being run from -
  which is the only way to see, from outside, that it took that lock at all.


  Both branches are committed first: a merge compares committed histories.

  ./dbgit commit -m everything staged so far
  DBGIT_WORKSPACE=$WORKSPACE_B ./dbgit commit -m readings index

  Part 1 - a write on '$BRANCH_B' blocks a merge run on '$BRANCH_A'.

  # terminal 2 - starts a ~20s index build, holding '$BRANCH_B'
  DBGIT_WORKSPACE=$WORKSPACE_B && echo $DBGIT_WORKSPACE && ./dbgit checkout $BRANCH_B
  ./dbgit add <<< "CREATE INDEX readings_id_payload_idx ON readings (id, payload);"

  # terminal 1, straight after - don't wait for terminal 2
  DBGIT_WORKSPACE="" && echo $DBGIT_WORKSPACE &&./dbgit checkout $BRANCH_A
  time ./dbgit merge $BRANCH_B

  '$BRANCH_A' is completely idle, and the merge is being run on it, yet its
  'real' is terminal 2's whole build. It waited for '$BRANCH_B' - a branch it
  only names as an argument. That is the second lock, visible.

  Part 2 - and the mirror, so it is not about which branch is 'the target'.

  # terminal 1 - a ~20s index build, holding '$BRANCH_A'
  ./dbgit add <<< "CREATE INDEX events_contended_payload_idx ON events (contended, payload);"

  # terminal 2, straight after
  time DBGIT_WORKSPACE=$WORKSPACE_B ./dbgit merge $BRANCH_A

  Afterwards, on either branch:

  ./dbgit branch     # no merge/... staging branch left behind
  ./dbgit log        # a (merge) commit, carrying no changesets of its own

  Blocked, not refused: the lock is pg_try_advisory_lock in a poll loop, so the
  merge retries until concurrency.lockTimeoutMs (60s) and only then gives up.
  To watch it give up instead of wait, drop lockTimeoutMs to 5000 in
  src/main/resources/dbgit.json, restart ./dbService, and run part 1 again:

      Branch '$BRANCH_B' is busy: another command has been holding it for
      more than 5s. Try again shortly.

  An error naming the OTHER branch, from a command run on this one.

--- Afterwards --------------------------------------------------------------

  ./dbgit checkout main
  ./scripts/clear-everything.sh -y    # branches, commits, workspace, and the
                                      # several GB these two branches hold

  Or just the two databases, keeping the rest of your dbgit state:

  docker exec -i \$BRANCH_CONTAINER psql -U \$BRANCH_USER -d postgres \\
      -c "DROP DATABASE \\"\$DB_A\\";" -c "DROP DATABASE \\"\$DB_B\\";"
  rm -rf \$WORKSPACE_B $STATE_FILE

CARD
