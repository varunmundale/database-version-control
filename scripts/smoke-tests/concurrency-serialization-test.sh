#!/usr/bin/env bash
# Asserts that changes to ONE branch are strictly serialized, however many handlers are free.
#
# This is the core guarantee. Each `dbgit add` validates the statement against the branch's replayed history
# before running it, so if two adds are allowed to overlap they both validate against the same past and both
# proceed - and the branch's recorded history then describes a schema its database does not have.
#
# Requires ./dbService running and Docker available.
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/concurrency-test-lib.sh"

CLIENTS=5
require_daemon

workspace="$(new_workspace serialization)"
branch="$(branch_name serialization)"

heading "Setting up branch '$branch'"
dbgit_in "$workspace" checkout -b "$branch" >/dev/null || exit 2
dbgit_add_in "$workspace" 'CREATE TABLE orders (id INT NOT NULL);' >/dev/null || exit 2

# ---------------------------------------------------------------------------------------------------------
heading "$CLIENTS clients racing to add the SAME column"
# Serialized, exactly one can win: the others replay the winner's change first and see the column already there.
# Unserialized, several validate against the empty table and all believe they succeeded.
outputs="$WORKDIR/same"
mkdir -p "$outputs"
for client in $(seq 1 "$CLIENTS"); do
    ( dbgit_add_in "$workspace" 'ALTER TABLE orders ADD COLUMN contended INT;' \
        >"$outputs/$client.out" 2>"$outputs/$client.err"; printf '%s' "$?" >"$outputs/$client.code" ) &
done
wait

succeeded=0
for client in $(seq 1 "$CLIENTS"); do
    [ "$(cat "$outputs/$client.code")" = "0" ] && succeeded=$((succeeded + 1))
done
assert_eq "exactly one of $CLIENTS concurrent adds of the same column succeeds" "$succeeded" "1"

rejections="$(cat "$outputs"/*.err 2>/dev/null)"
assert_contains "the losers are told the column already exists" "$rejections" "Column already exists"

log="$(dbgit_in "$workspace" log)"
assert_eq "the branch recorded one base table plus one contended column" "$(working_set_size "$log")" "2"

# ---------------------------------------------------------------------------------------------------------
heading "$CLIENTS clients adding DIFFERENT columns at once"
# All of these are legitimate, so all must succeed - serialization must order work, not refuse it.
outputs="$WORKDIR/distinct"
mkdir -p "$outputs"
for client in $(seq 1 "$CLIENTS"); do
    ( dbgit_add_in "$workspace" "ALTER TABLE orders ADD COLUMN col$client INT;" \
        >"$outputs/$client.out" 2>"$outputs/$client.err"; printf '%s' "$?" >"$outputs/$client.code" ) &
done
wait

succeeded=0
for client in $(seq 1 "$CLIENTS"); do
    [ "$(cat "$outputs/$client.code")" = "0" ] && succeeded=$((succeeded + 1))
done
assert_eq "all $CLIENTS distinct columns are accepted" "$succeeded" "$CLIENTS"

log="$(dbgit_in "$workspace" log)"
assert_eq "the working set holds every change exactly once" "$(working_set_size "$log")" "$((CLIENTS + 2))"

summary
