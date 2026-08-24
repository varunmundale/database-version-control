#!/usr/bin/env bash
# Walkthrough of DROP TABLE and ALTER TABLE ... RENAME TO. Requires ./dbService to already be running.
#
# A table renamed on one side of a fork reads as one table that moved (not a drop plus an add), an
# index survives the rename by stable id, the rename merges cleanly into the branch that never
# touched it, and a fork of the result replays both the drop and the rename faithfully.
#
# table-commands-rejected-demo.sh is the other half of this walkthrough: every conditional or
# multi-table form of these two statements that dbgit must refuse.
set -euo pipefail
cd "$(dirname "$0")/../.."

BASE="table-cmds/$$"
RENAMED="table-cmds-renamed/$$"
FORKED="table-cmds-forked/$$"

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "table-commands-demo"

echo "=== $BASE: two tables, an index, then a drop ==="
./dbgit checkout -b "$BASE"
./dbgit add <<'EOF'
CREATE TABLE orders (id INT NOT NULL, total NUMERIC(10,2));
EOF
./dbgit add <<'EOF'
CREATE TABLE scratch (id INT NOT NULL);
EOF
./dbgit add <<'EOF'
CREATE INDEX orders_total_idx ON orders (total);
EOF
./dbgit add <<'EOF'
DROP TABLE scratch;
EOF
./dbgit commit

echo
echo "=== Fork '$RENAMED', rename orders -> purchases ==="
./dbgit checkout -b "$RENAMED"
./dbgit add <<'EOF'
ALTER TABLE orders RENAME TO purchases;
EOF
./dbgit commit

echo
echo "=== dbgit diff: one table that moved, not a drop plus an add ==="
./dbgit diff "$BASE" "$RENAMED"

echo
echo "=== Merge the rename back into $BASE, which never touched the table's identity ==="
./dbgit checkout "$BASE"
./dbgit merge "$RENAMED"
./dbgit diff "$BASE" "$RENAMED"

echo
echo "=== Fork '$FORKED' from $BASE: a fork never introspects, only replays - this proves the drop and the rename replay faithfully ==="
./dbgit checkout -b "$FORKED"
./dbgit log | head -5
