#!/usr/bin/env bash
# Asserts that an operation which fails part-way leaves nothing broken behind.
#
# The metadata write and the real database change cannot be one transaction - dbgit records a changeset, then runs
# DDL, then marks it applied; it claims a branch name, then builds that branch's database. Anything that fails in
# between has to be compensated for, or the leftovers are worse than the failure: a changeset stuck at PENDING can
# never be committed yet still counts as working set, and a claimed-but-unbuilt branch name can never be reused.
#
# Requires ./dbService running and Docker available.
set -uo pipefail
source "$(cd "$(dirname "$0")" && pwd)/concurrency-test-lib.sh"

require_daemon

workspace="$(new_workspace recovery)"
branch="$(branch_name recovery)"

heading "Setting up branch '$branch'"
dbgit_in "$workspace" checkout -b "$branch" >/dev/null || exit 2
dbgit_add_in "$workspace" 'CREATE TABLE orders (id INT NOT NULL);' >/dev/null || exit 2

# ---------------------------------------------------------------------------------------------------------
heading "DDL that dbgit accepts but PostgreSQL rejects"
# The statement parses and the column model accepts it, so a changeset row is written - and only then does the
# database refuse the type. That row describes something which never happened and must not survive.
before="$(working_set_size "$(dbgit_in "$workspace" log)")"
if dbgit_add_in "$workspace" 'ALTER TABLE orders ADD COLUMN broken NOTATYPE;' >/dev/null 2>&1; then
    bad "the database should have rejected an unknown column type" "the add unexpectedly succeeded"
else
    ok "the database rejected the statement"
fi
after="$(working_set_size "$(dbgit_in "$workspace" log)")"

assert_eq "no changeset is left behind for DDL that never ran" "$after" "$before"
assert_not_contains "and the failed statement is nowhere in the branch's history" \
    "$(dbgit_in "$workspace" log)" "NOTATYPE"

# The branch is still usable afterwards, rather than wedged behind a stuck changeset.
if dbgit_add_in "$workspace" 'ALTER TABLE orders ADD COLUMN recovered INT;' >/dev/null 2>&1; then
    ok "the branch still accepts work after the failure"
else
    bad "the branch still accepts work after the failure" "a later add was refused"
fi

# ---------------------------------------------------------------------------------------------------------
heading "Merging cleans up after itself"
# A merge forks a scratch branch to prove the replay before touching the target's real database. That scratch
# branch is an implementation detail: it must not be left lying around, and its name must not collide with the
# next merge of the same pair.
source_branch="$(branch_name merge-source)"
dbgit_in "$workspace" commit -m base >/dev/null || exit 2
dbgit_in "$workspace" checkout -b "$source_branch" >/dev/null || exit 2
dbgit_add_in "$workspace" 'ALTER TABLE orders ADD COLUMN merged_in INT;' >/dev/null || exit 2
dbgit_in "$workspace" commit -m "a change to merge" >/dev/null || exit 2
dbgit_in "$workspace" checkout "$branch" >/dev/null || exit 2

merge_output="$(dbgit_in "$workspace" merge "$source_branch" 2>&1)"
assert_contains "the merge reports the staging branch it validated through" "$merge_output" "Validated via staging branch"
assert_not_contains "no staging branch is left behind" "$(dbgit_in "$workspace" branch)" "merge/"

summary
