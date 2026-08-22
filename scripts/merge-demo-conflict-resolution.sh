#!/usr/bin/env bash
# Demo walkthrough of RESOLVING a conflicting dbgit merge via a compensating DDL statement.
# Requires ./dbService to already be running.
#
# Story:
#   - main: creates the base 'invoices' table, commits
#   - num: forked from main, retypes 'amount' to NUMERIC(14,4), commits
#   - big: forked from main, retypes the SAME 'amount' column to BIGINT, commits
#   - dbgit merge big (while on num) -> rejected: both sides touched the same column
#     incompatibly since they diverged.
#   - Resolution: dbgit has no automatic conflict resolution, so the way past a genuine
#     conflict is the same as staging any other change - add a compensating DDL statement
#     with 'dbgit add' + 'dbgit commit' that makes the two branches agree again on the
#     conflicting column, then retry the merge. Here the compensation lands on 'num' itself,
#     retyping 'amount' to the same BIGINT type 'big' already settled on.
#   - dbgit merge big (while on num) -> succeeds this time, since the conflict check no
#     longer finds a difference to flag.
set -euo pipefail
cd "$(dirname "$0")/.."

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

echo "=== Starting on main ==="
# main is no longer an implicit scratchpad database - it tracks a real one, named here. Re-running
# this is harmless: the same target signs the same, so init is idempotent.
./dbgit init --host localhost --port 55432 --database postgres --user postgres --password postgres

./dbgit checkout main

echo
echo "=== main: create the base table ==="
./dbgit add <<'EOF'
CREATE TABLE invoices (
    id SERIAL,
    amount DECIMAL(10, 2)
);
EOF

# CREATE TABLE defines columns only; constraints are their own statements.
./dbgit add <<'EOF'
ALTER TABLE invoices ADD CONSTRAINT invoices_pkey PRIMARY KEY (id);
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
echo "=== On num, attempting to merge big into it (expected to be rejected) ==="
./dbgit checkout num
if ./dbgit merge big; then
    echo "ERROR: expected the merge to be rejected due to a conflict, but it succeeded." >&2
    exit 1
fi
echo "--> Merge was rejected, as expected (see the error above) - note the error names the"
echo "    conflicting column and points at compensating with 'dbgit add' + 'dbgit commit'."

echo
echo "=== Resolving: add a compensating statement on 'num' that reconciles the two branches ==="
echo "num currently has amount as NUMERIC(14,4); retype it to BIGINT, the same type 'big'"
echo "already settled on. Once both branches agree on the final type, the conflict is gone."
./dbgit add <<'EOF'
ALTER TABLE invoices ALTER COLUMN amount TYPE BIGINT;
EOF
./dbgit commit

echo
echo "=== Diff after the compensating commit (amount no longer flagged as conflicting) ==="
./dbgit diff num big

echo
echo "=== Retrying the merge - it succeeds now ==="
./dbgit merge big

echo
echo "=== num's real database: 'amount' is BIGINT, brought together by the compensating commit ==="
./dbgit branch
