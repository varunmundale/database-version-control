#!/usr/bin/env bash
# Demo walkthrough of RESOLVING a conflicting dbgit merge via a compensating DDL statement.
# Requires ./dbService to already be running.
#
# Story: 'num' and 'big' both fork from main and retype the same column incompatibly, so merging
# one into the other is rejected. dbgit has no automatic conflict resolution - the fix is to stage a
# compensating statement (here, retyping 'num's column to match 'big's) so the branches agree again,
# then retry the merge.
set -euo pipefail
cd "$(dirname "$0")/../.."

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

echo "=== Starting on main ==="
# main is no longer an implicit scratchpad database - it tracks a real one, named here. Re-running
# this is harmless: the same target signs the same, so init is idempotent.
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "merge-demo-conflict-resolution"

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
