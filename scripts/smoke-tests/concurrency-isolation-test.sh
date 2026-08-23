#!/usr/bin/env bash
# Asserts that two callers of one daemon are independent: separate current branches, and work on different
# branches proceeding at the same time rather than queueing behind each other.
#
# The daemon used to keep the current branch in a single file in its own working directory, so one user's
# `checkout` silently moved everyone else's. Now each workspace holds its own HEAD and sends it per request.
#
# Requires ./dbService running and Docker available.
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/concurrency-test-lib.sh"

require_daemon

alice="$(new_workspace alice)"
bob="$(new_workspace bob)"
alice_branch="$(branch_name alice)"
bob_branch="$(branch_name bob)"

heading "Two workspaces, one daemon"
dbgit_in "$alice" checkout -b "$alice_branch" >/dev/null || exit 2
dbgit_in "$bob" checkout -b "$bob_branch" >/dev/null || exit 2

assert_eq "alice's workspace is on her own branch" "$(cat "$alice/.dbgit/HEAD")" "$alice_branch"
assert_eq "bob's workspace is on his own branch" "$(cat "$bob/.dbgit/HEAD")" "$bob_branch"

# The daemon must report each caller's branch back to that caller, not one shared value.
assert_contains "alice sees herself on her branch" "$(dbgit_in "$alice" branch)" "* $alice_branch"
assert_contains "bob sees himself on his branch" "$(dbgit_in "$bob" branch)" "* $bob_branch"

heading "Alice checks out somewhere else"
third_branch="$(branch_name third)"
dbgit_in "$alice" checkout -b "$third_branch" >/dev/null || exit 2

assert_eq "alice moved" "$(cat "$alice/.dbgit/HEAD")" "$third_branch"
assert_eq "bob did not move with her" "$(cat "$bob/.dbgit/HEAD")" "$bob_branch"

heading "Both work at the same time, on different branches"
dbgit_in "$alice" checkout "$alice_branch" >/dev/null || exit 2
( dbgit_add_in "$alice" 'CREATE TABLE orders (id INT NOT NULL);' >"$WORKDIR/alice.out" 2>&1; \
  printf '%s' "$?" >"$WORKDIR/alice.code" ) &
( dbgit_add_in "$bob" 'CREATE TABLE invoices (id INT NOT NULL);' >"$WORKDIR/bob.out" 2>&1; \
  printf '%s' "$?" >"$WORKDIR/bob.code" ) &
wait

assert_eq "alice's change was accepted" "$(cat "$WORKDIR/alice.code")" "0"
assert_eq "bob's change was accepted" "$(cat "$WORKDIR/bob.code")" "0"

# Per-branch locking must not leak work between branches.
alice_log="$(dbgit_in "$alice" log)"
bob_log="$(dbgit_in "$bob" log)"
assert_contains "alice's branch has her table" "$alice_log" "CREATE TABLE orders"
assert_not_contains "alice's branch does not have bob's" "$alice_log" "CREATE TABLE invoices"
assert_contains "bob's branch has his table" "$bob_log" "CREATE TABLE invoices"
assert_not_contains "bob's branch does not have alice's" "$bob_log" "CREATE TABLE orders"

summary
