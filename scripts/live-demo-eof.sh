#!/usr/bin/env bash
# The live walkthrough, as a runnable script: every `dbgit add` takes its DDL on stdin via a heredoc,
# which is what the client actually expects (DDL can span lines, so `add` reads the statement from
# stdin rather than argv). Requires ./dbService to already be running.
#
# Story:
#   - current: creates 'employees', commits, adds a column of its own
#   - other:   forked from current, adds a column of its own
#   - current merges other, then retypes 'department' to varchar(10)
#   - other retypes the SAME column to varchar(20) -> merging current into other is refused
#   - other compensates, retyping 'department' back to what the shared history declared, and the
#     merge goes through
#
# Two things this exercises that used to be wrong, both marked below:
#   - merging back the other way after a merge (histories compared as sets of commit ids, not as
#     positional prefixes)
#   - a conflict judged three-way, against the history both branches share, rather than by comparing
#     the two branches' schemas alone
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

# Show postgres state.
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


# Show postgres state.

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
# Merging back the other way round used to fail here: the two histories were compared position by
# position, so once 'current' had merged 'other', the commit they shared sat at a different index on
# each side and already-shared changesets were replayed a second time. Compared as sets of commit
# ids, it is only ever the commits the other side genuinely lacks that get replayed.
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
# A two-way comparison cannot settle this: with only the two branches' schemas to look at, a column
# that differs is a conflict no matter who changed it, so the statement written to resolve the
# conflict could never clear it. Judged against the history both branches share, 'other' is back
# where it started and has changed nothing - only 'current' has moved the column.
./dbgit add <<'EOF'
ALTER TABLE employees ALTER COLUMN department TYPE varchar(100);
EOF
./dbgit commit

echo
echo "=== diff other current (no longer conflicting) ==="
./dbgit diff other current

#echo
#echo "=== merge current into other ==="
#./dbgit merge current
#./dbgit log

# The other way to resolve the same conflict: instead of compensating, reset 'other' back to its own
# last commit, dropping the retype altogether. Same outcome - only 'current' has moved the column, so
# the merge brings its change in. Capture the commit to reset to when it is created, rather than
# hard-coding a number that depends on what else the metadata store has seen:
#
#     other_own_commit="$(./dbgit commit | sed -n 's/^Created commit #\([0-9]*\).*/\1/p')"
#     ...
#     ./dbgit log
#     ./dbgit reset "$other_own_commit"
#     ./dbgit merge current
