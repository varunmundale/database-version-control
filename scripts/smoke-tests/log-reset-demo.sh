#!/usr/bin/env bash
# Walkthrough of `dbgit log` and `dbgit reset`. Requires ./dbService to already be running.
#
# Builds a branch with two commits plus an uncommitted working set, shows the log, then resets the
# branch back to its first commit - which drops the working set and rebuilds the branch database by
# replaying only the history up to that commit.
set -euo pipefail
cd "$(dirname "$0")/../.."

BRANCH="log-reset-demo/$$"

# Reset local repo state so the branch this script creates starts fresh.
rm -rf .dbgit/
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "log-reset-demo"

./dbgit checkout -b "$BRANCH"

./dbgit add <<'EOF'
CREATE TABLE orders (
    id INT NOT NULL,
    placed_on DATE
);
EOF
./dbgit commit -m create the orders table
FIRST_COMMIT=$(./dbgit log | sed -n 's/^commit #\([0-9]*\).*/\1/p' | head -1)

./dbgit add <<'EOF'
ALTER TABLE orders ADD COLUMN total NUMERIC(10,2);
EOF
./dbgit commit -m add a total column

# Staged but never committed - this is the working set the log shows above the commits.
./dbgit add <<'EOF'
ALTER TABLE orders ADD COLUMN note TEXT;
EOF

echo
echo "=== dbgit log: working set on top, then commits newest first ==="
./dbgit log

echo
echo "=== dbgit reset $FIRST_COMMIT: back to the first commit ==="
./dbgit reset "$FIRST_COMMIT"

echo
echo "=== dbgit log after the reset: one commit, no working set ==="
./dbgit log

echo
echo "Reset is refused on 'main', which tracks a real database rather than a scratchpad fork:"
./dbgit checkout main
./dbgit reset "$FIRST_COMMIT" || echo "(refused, as expected)"
