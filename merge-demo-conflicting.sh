#!/usr/bin/env bash
# Demo walkthrough of a CONFLICTING dbgit merge. Requires ./dbService to already be running.
#
# Story:
#   - main: creates the base 'invoices' table, commits
#   - num: forked from main, retypes 'amount' to NUMERIC(14,4), commits
#   - big: forked from main, retypes the SAME 'amount' column to BIGINT, commits
#   - dbgit merge big (while on num) -> rejected: both sides touched the same column
#     incompatibly since they diverged. No staging branch or merge commit is created.
set -euo pipefail
cd "$(dirname "$0")"

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

echo "=== Starting on main ==="
./dbgit checkout main

echo
echo "=== main: create the base table ==="
./dbgit add <<'EOF'
CREATE TABLE invoices (
    id SERIAL PRIMARY KEY,
    amount DECIMAL(10, 2)
);
EOF
./dbgit commit

echo
echo "=== Fork 'num' from main, retype 'amount' to NUMERIC(14,4) ==="
./dbgit checkout -b num

./dbgit add <<'EOF'
ALTER TABLE invoices ALTER COLUMN amount TYPE NUMERIC(14, 4);
EOF
./dbgit commit

echo
echo "=== Fork 'big' from main too, retype the SAME column to BIGINT ==="
./dbgit checkout main
./dbgit checkout -b big

./dbgit add <<'EOF'
ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;
EOF
./dbgit commit

echo
echo "=== Diff before merge (same column, retyped differently on each side -> flagged as conflicting) ==="
./dbgit diff num big

echo
echo "=== On num, attempting to merge big into it (expected to be rejected) ==="
./dbgit checkout num
if ./dbgit merge big; then
    echo "ERROR: expected the merge to be rejected due to a conflict, but it succeeded." >&2
    exit 1
fi
echo "--> Merge was rejected, as expected (see the error above)."

echo
echo "=== Branch list (no 'merge/...' staging branch - the merge never got that far) ==="
./dbgit branch

echo
echo "=== num's history is untouched: still just the two commits from before the merge attempt ==="
./dbgit diff num big
