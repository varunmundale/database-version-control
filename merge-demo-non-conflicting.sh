#!/usr/bin/env bash
# Demo walkthrough of a NON-CONFLICTING dbgit merge. Requires ./dbService to already be running.
#
# Story:
#   - main: creates the base 'products' table, commits
#   - sku: forked from main, adds a 'sku' column, commits
#   - notes: forked from main, adds a different 'notes' column, commits
#   - dbgit merge notes (while on sku) -> no conflict, since the two branches touched
#     different columns; sku ends up with both 'sku' and 'notes'.
set -euo pipefail
cd "$(dirname "$0")"

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
CREATE TABLE products (
    id SERIAL,
    name VARCHAR(100) NOT NULL,
    price DECIMAL(10, 2)
);
EOF

# CREATE TABLE defines columns only; constraints are their own statements.
./dbgit add <<'EOF'
ALTER TABLE products ADD CONSTRAINT products_pkey PRIMARY KEY (id);
EOF
./dbgit commit

echo
echo "=== Fork 'sku' from main, add a 'sku' column ==="
./dbgit checkout -b sku

./dbgit add <<'EOF'
ALTER TABLE products ADD COLUMN sku VARCHAR(50);
EOF
./dbgit commit

echo
echo "=== Fork 'notes' from main too, add a different 'notes' column ==="
./dbgit checkout main
./dbgit checkout -b notes

./dbgit add <<'EOF'
ALTER TABLE products ADD COLUMN notes TEXT;
EOF
./dbgit commit

echo
echo "=== Diff before merge (two independent, non-conflicting column additions) ==="
./dbgit diff sku notes

echo
echo "=== On sku, merging notes into it ==="
./dbgit checkout sku
./dbgit merge notes

echo
echo "=== Diff after merge ==="
echo "sku now has both 'sku' and 'notes'; notes only ever gained 'notes', so 'sku' still"
echo "shows up as a one-sided difference - merge only pulls the other branch's changes in,"
echo "it doesn't push the current branch's own changes back out."
./dbgit diff sku notes

echo
echo "=== Branch list (note the internal staging branch dbgit merge created to validate the merge) ==="
./dbgit branch
