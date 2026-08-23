#!/usr/bin/env bash
# Sets up a branch-lock demo, then prints the commands for you to type in two terminals.
# Requires ./dbService running and Docker up.
#
# dbgit is faster than a person: a branch is held for the few milliseconds a command takes, so two
# commands typed by hand can never collide. Nothing here fakes a lock. The branch just gets a table
# with twelve million rows in it, so that a real CREATE INDEX takes real time and holds the branch
# for as long as PostgreSQL needs - which is the case branch locks exist for.
#
# The rows go in with psql because dbgit versions schema, not data; it has no command for them.
set -euo pipefail
rm -rf .dbgit/

BRANCH="lockdemo"
ROWS=12000000                        # ~15s to load, and gives you a ~20s window. Turn it up if
                                     # you talk slowly; the index build scales with it.
PSQL="docker exec -i postgres-branches-scratchpad psql -U postgres"

./dbgit branch >/dev/null 2>&1 || { printf 'Start the daemon first:\n\n    ./dbService\n\n' >&2; exit 2; }
WAS_ON="$(cat .dbgit/HEAD 2>/dev/null || echo main)"

echo "=== Preparing '$BRANCH' (you are on '$WAS_ON') ==="
./dbgit checkout -b "$BRANCH"
./dbgit add <<<"CREATE TABLE events (id INT, payload TEXT);"
./dbgit commit -m base table

echo "Loading $ROWS rows into ${BRANCH}_postgres..."
$PSQL -q -d "${BRANCH}_postgres" -c "INSERT INTO events SELECT g, md5(g::text) FROM generate_series(1, $ROWS) g;"
echo "Loaded."

cat <<CARD

##############################################################################
#  Ready. You are on '$BRANCH'.
#  Open a second terminal in this same directory - that is what puts it on the
#  same branch, since the branch lives in .dbgit/HEAD, not in the daemon.
##############################################################################

--- Act 1: five commands race for the same column ---------------------------

  for i in 1 2 3 4 5 ; do
      ./dbgit add <<< "ALTER TABLE events ADD COLUMN contended INT;" &
  done; wait

  One "Applied changeset #N", four "Column already exists: contended". Each
  loser is seeing what the winner did - which is only possible because they
  went through the branch one at a time.

  ./dbgit log      # one contended column in the working set. Not five.

--- Act 2: a migration holds the branch, the next command waits -------------

  # terminal 1 - real work on $ROWS rows
  time ./dbgit add <<< "CREATE INDEX events_payload_idx ON events (payload);"


  # terminal 2 - straight after, don't wait for terminal 1
  time ./dbgit add <<< "ALTER TABLE events ADD COLUMN waited_for_the_index DATE;"

  Terminal 2 hangs, then goes through the moment terminal 1 returns. Its own
  work is instant: it waited for the branch, not for PostgreSQL. The two 'real'
  times end together.

-----ACT 3:
  <drop index from UI>
  time ./dbgit add <<< "CREATE INDEX events_payload_idx ON events (payload);"

  # terminal 2, while terminal 1 is still building - reads take no lock
  ./dbgit log

  Answers immediately. log, diff and branch only read history, so a long
  migration never blocks anyone looking at the branch.

--- Afterwards --------------------------------------------------------------

  ./dbgit checkout $WAS_ON
  $PSQL -d postgres -c 'DROP DATABASE "${BRANCH}_postgres";'   # ~1.5GB of rows

CARD
