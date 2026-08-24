#!/usr/bin/env bash
# Checks that dbgit rejects every conditional or multi-table form of DROP TABLE and
# ALTER TABLE ... RENAME TO, and that it says what to write instead. Requires ./dbService running.
#
# Unlike table-commands-demo.sh this one asserts: it exits non-zero if dbgit ever accepts a
# statement it should have refused. dbgit rebuilds a schema by replaying a history, so a statement
# has to mean the same thing every time it runs - the reason behind every rejection here.
set -uo pipefail
cd "$(dirname "$0")/../.."

BRANCH="table-cmds-rejected/$$"
failures=0

# Reset local repo state so the branch this script creates starts fresh.
rm -rf .dbgit/
./dbgit init --host localhost --port 5433 --database postgres --user postgres --password postgres --author "table-commands-rejected-demo"
./dbgit checkout -b "$BRANCH"
./dbgit add <<'EOF'
CREATE TABLE purchases (id INT NOT NULL);
EOF

# Runs one statement that dbgit must refuse, and checks the reason it gives mentions `expected`.
expect_rejected() {
    local description="$1" expected="$2" ddl="$3" output status
    printf '\n--- %s\n' "$description"
    printf '    %s\n' "$ddl"
    set +e
    output=$(printf '%s\n' "$ddl" | ./dbgit add 2>&1 | grep -v '^WARNING:')
    status=${PIPESTATUS[0]}
    set -e
    if [ "$status" -eq 0 ]; then
        printf '    FAIL: accepted, but should have been rejected\n'
        failures=$((failures + 1))
        return
    fi
    if printf '%s' "$output" | grep -qF "$expected"; then
        printf '    rejected: %s\n' "$output"
    else
        printf '    FAIL: rejected, but the reason did not mention "%s"\n' "$expected"
        printf '    got: %s\n' "$output"
        failures=$((failures + 1))
    fi
}

echo
echo "=== A statement must mean the same thing every time it is replayed - these are all refused ==="

expect_rejected "DROP TABLE IF EXISTS - means one thing on a branch with the table, another without" \
    "IF EXISTS" \
    "DROP TABLE IF EXISTS purchases;"

expect_rejected "DROP TABLE ... CASCADE - can silently drop constraints on other tables" \
    "CASCADE" \
    "DROP TABLE purchases CASCADE;"

expect_rejected "DROP TABLE naming several tables at once" \
    "Could not parse" \
    "DROP TABLE purchases, orders;"

expect_rejected "CREATE TABLE IF NOT EXISTS - same reason, the other direction" \
    "IF NOT EXISTS" \
    "CREATE TABLE IF NOT EXISTS purchases (id INT);"

expect_rejected "ALTER TABLE IF EXISTS - not just on RENAME TO, on every ALTER form" \
    "IF EXISTS" \
    "ALTER TABLE IF EXISTS purchases RENAME TO sales;"

expect_rejected "RENAME TABLE ... TO ... - MySQL's own spelling; write ALTER TABLE ... RENAME TO instead" \
    "ALTER TABLE" \
    "RENAME TABLE purchases TO sales;"

echo
echo "--- (creating a second table so the next check has a name to clash with)"
./dbgit add <<'EOF'
CREATE TABLE sales (id INT NOT NULL);
EOF

expect_rejected "renaming onto a name already in use" \
    "already exists" \
    "ALTER TABLE purchases RENAME TO sales;"

echo
if [ "$failures" -eq 0 ]; then
    echo "All statements behaved as expected."
else
    echo "$failures statement(s) did not behave as expected."
    exit 1
fi
