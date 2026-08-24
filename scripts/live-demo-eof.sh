#!/usr/bin/env bash
# The live walkthrough, as a runnable script. Requires ./dbService to already be running.
#
# Story: current creates 'employees' and commits; other forks from current and adds its own column;
# current merges other, then retypes 'department'; other retypes the SAME column differently, so
# merging current into other is refused; other compensates back to the shared type, and the merge
# goes through. Then the same shape again with a column rename instead of a retype.
#
# Branch names are fixed, so this wants a metadata store that does not already have 'current' and
# 'other' - the same as the other merge demos.
set -euo pipefail
cd "$(dirname "$0")/.."

# Reset local repo state so the branches this script creates start fresh.
rm -rf .dbgit/

# main tracks a real database, named here. Re-running this is harmless: the same target signs the
# same, so init is idempotent.
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "varun"

./dbgit log
./dbgit branch

echo
echo "=== current: create the base table ==="
./dbgit checkout -b current
./dbgit add <<'EOF'
CREATE TABLE employees (
    id SERIAL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(150),
    age INT,
    salary DECIMAL(10, 2),
    department VARCHAR(100)
);
EOF
./dbgit commit

echo
echo "=== other: forked from current, a column of its own ==="
./dbgit checkout -b other
./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN other_column DATE;
EOF
./dbgit commit
./dbgit log


echo
echo "=== current: a column of its own ==="
./dbgit checkout current
./dbgit add <<'EOF'
ALTER TABLE employees ADD COLUMN current_column DATE;
EOF
./dbgit commit
./dbgit log

echo
echo "=== merge other into current ==="
./dbgit checkout current
./dbgit merge other
./dbgit log

echo
echo "=== current: retype 'department' ==="
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN department TYPE varchar(10);
EOF
./dbgit commit

echo
echo "=== other: retype the SAME column, differently ==="
./dbgit checkout other
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN department TYPE varchar(20);
EOF
./dbgit commit

echo
echo "=== diff other current ==="
# Histories are compared as sets of commit ids, not positional prefixes - so this works even though
# 'current' already merged 'other' and their shared commit now sits at a different index on each side.
./dbgit diff other current

echo
echo "=== merge current into other (expected to be refused: both branches moved 'department') ==="
if ./dbgit merge current; then
    echo "ERROR: expected the merge to be refused due to a conflict, but it succeeded." >&2
    exit 1
fi
echo "--> Merge was refused, as expected (see the error above)."

echo
echo "=== other: compensate, back to the type the shared history declared ==="
# Judged against the history both branches share, 'other' is back where it started and has changed
# nothing - only 'current' has moved the column, so the conflict clears.
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);
EOF
./dbgit commit

echo
echo "=== diff other current (no longer conflicting) ==="
./dbgit diff other current

########################################################################################
# Renames: a column's stable id is assigned once and carried through RENAME COLUMN, so a diff reports
# ONE column that moved rather than a drop plus an add. Tables get no such treatment - they are
# matched by name, so renaming a table does read as a drop plus an add.
########################################################################################

echo
echo "=== current: an index over the column that is about to be renamed ==="
./dbgit checkout current
./dbgit add <<'EOF'
CREATE INDEX employees_email_idx ON employees (email);
EOF
./dbgit commit

echo
echo "=== current: rename that column ==="
./dbgit add <<'EOF'
ALTER TABLE employees RENAME COLUMN email TO contact_email;
EOF
./dbgit commit

echo
echo "=== diff other current (a one-sided rename: one column moved, and it is not a conflict) ==="
# One node for that column, not two, and no '(conflicting)' - only 'current' moved it. The index
# still covers the same column too: IndexModel holds stable ids, not names.
./dbgit diff other current

echo
echo "=== merge current into other ==="
# Goes through: a one-sided rename is just a change the other branch does not have yet.
./dbgit checkout other
./dbgit merge current
./dbgit log

echo
echo "=== other: rename a different column ==="
./dbgit add <<'EOF'
ALTER TABLE employees RENAME COLUMN name TO full_name;
EOF
./dbgit commit

echo
echo "=== current: retype the SAME column ==="
./dbgit checkout current
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN name TYPE varchar(200);
EOF
./dbgit commit

echo
echo "=== diff other current (conflicting: both sides moved the same column, under two names) ==="
# Without the id carried through the rename these would look like two unrelated columns.
./dbgit diff other current

echo
echo "=== merge current into other (expected to be refused: both branches moved 'name') ==="
./dbgit checkout other
if ./dbgit merge current; then
    echo "ERROR: expected the merge to be refused due to a rename conflict, but it succeeded." >&2
    exit 1
fi
echo "--> Merge was refused, as expected (see the error above)."

echo
echo "=== other: compensate, renaming the column back ==="
# Puts 'other' back where the shared history left the column, and gives current's statement (which
# names 'name') a column of that name to land on when the merge replays it.
./dbgit add <<'EOF'
ALTER TABLE employees RENAME COLUMN full_name TO name;
EOF
./dbgit commit

echo
echo "=== diff other current (no longer conflicting) ==="
./dbgit diff other current

echo
echo "=== merge current into other ==="
./dbgit merge current
./dbgit log
