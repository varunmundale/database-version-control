#!/usr/bin/env bash
# Asserts that concurrent commits on one branch do not lose each other.
#
# The failure this guards against is the worst one in the system, and it is silent. `commit` reads the branch's
# HEAD, then writes a new commit parented on it. Two commits reading the same HEAD both succeed, the second
# overwrites it, and the first becomes reachable from nothing - while its changesets are already marked COMMIT,
# so neither the working set nor a reset can ever find them again. Nothing reports it; the changes are simply
# gone from the branch's history.
#
# Requires ./dbService running and Docker available.
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/concurrency-test-lib.sh"

COMMITTERS=4
require_daemon

owner="$(new_workspace committer-0)"
branch="$(branch_name commit-race)"

heading "A branch with one committed table"
dbgit_in "$owner" checkout -b "$branch" >/dev/null || exit 2
dbgit_add_in "$owner" 'CREATE TABLE orders (id INT NOT NULL);' >/dev/null || exit 2
dbgit_in "$owner" commit -m base table >/dev/null || exit 2

heading "$COMMITTERS clients adding and committing at once"
# Each client stages its own column and commits. Whose changeset ends up in whose commit is not determined -
# what must hold is that every one of them is still reachable by walking the branch's commits afterwards.
for client in $(seq 1 "$COMMITTERS"); do
    workspace="$(new_workspace "committer-$client")"
    # new_workspace already gave this committer its own config.json (author "committer-$client"); only HEAD needs
    # to match the owner's, so this committer is on the right branch.
    cp "$owner/.dbgit/HEAD" "$workspace/.dbgit/HEAD"
    (
        dbgit_add_in "$workspace" "ALTER TABLE orders ADD COLUMN col$client INT;" >/dev/null 2>&1
        dbgit_in "$workspace" commit -m "add col$client" >/dev/null 2>&1
    ) &
done
wait

log="$(dbgit_in "$owner" log)"
committed="$(committed_changeset_ids "$log" | sort -n)"
distinct="$(printf '%s\n' "$committed" | sort -nu)"

assert_eq "every changeset staged on the branch is still reachable from HEAD" \
    "$(printf '%s\n' "$committed" | grep -c .)" "$((COMMITTERS + 1))"
assert_eq "and none of them is reachable twice" \
    "$(printf '%s\n' "$distinct" | grep -c .)" "$((COMMITTERS + 1))"
assert_eq "nothing is left uncommitted" "$(working_set_size "$log")" "0"

for client in $(seq 1 "$COMMITTERS"); do
    assert_contains "col$client survived the race" "$log" "ADD COLUMN col$client"
done

summary
