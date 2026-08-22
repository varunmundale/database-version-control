#!/usr/bin/env bash
# Checks that dbgit rejects every way of smuggling a constraint or an index into a CREATE TABLE,
# and that it says what to write instead. Requires ./dbService to already be running.
#
# Unlike the other demo scripts this one asserts: it exits non-zero if dbgit ever accepts a
# statement it should have refused. Nothing here is expected to succeed, so it creates no tables
# and leaves no schema behind - it is safe to run against a live workspace.
set -uo pipefail
cd "$(dirname "$0")/.."

failures=0

# Runs one statement that dbgit must refuse, and checks the reason it gives mentions `expected`.
expect_rejected() {
    local description="$1" expected="$2" ddl="$3" output status
    printf '\n--- %s\n' "$description"
    printf '    %s\n' "$(printf '%s' "$ddl" | tr '\n' ' ')"
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

echo "=== Constraints and indexes must be added separately - these are all refused ==="

expect_rejected "PRIMARY KEY written on a column" \
    "ADD CONSTRAINT rejected_demo_pkey PRIMARY KEY (id)" \
    "CREATE TABLE rejected_demo (id INT PRIMARY KEY);"

expect_rejected "UNIQUE written on a column" \
    "declares UNIQUE" \
    "CREATE TABLE rejected_demo (email TEXT UNIQUE);"

expect_rejected "REFERENCES (a foreign key) written on a column" \
    "FOREIGN KEY" \
    "CREATE TABLE rejected_demo (customer_id INT REFERENCES customers(id));"

expect_rejected "PRIMARY KEY as a table-level clause in the body" \
    "CREATE TABLE defines columns only" \
    "CREATE TABLE rejected_demo (id INT, PRIMARY KEY (id));"

expect_rejected "a named UNIQUE constraint in the body" \
    "CREATE TABLE defines columns only" \
    "CREATE TABLE rejected_demo (id INT, name TEXT, CONSTRAINT uq UNIQUE (name));"

expect_rejected "CHECK, which the model has no room for" \
    "CREATE TABLE defines columns only" \
    "CREATE TABLE rejected_demo (id INT, CHECK (id > 0));"

expect_rejected "CHECK added separately - still unsupported, but for a different reason" \
    "Unsupported constraint type" \
    "ALTER TABLE orders ADD CONSTRAINT positive CHECK (id > 0);"

expect_rejected "an unnamed constraint, whose name would be its identity" \
    "ALTER TABLE ADD CONSTRAINT" \
    "ALTER TABLE orders ADD PRIMARY KEY (id);"

expect_rejected "DROP INDEX, which names no table to attribute it to" \
    "DROP INDEX is not supported" \
    "DROP INDEX idx_orders_total;"

echo
echo "=== NOT NULL and DEFAULT are properties of the column, so they stay allowed ==="
echo "    (not run here - see constraints-indexes-demo.sh, which uses both)"

echo
if [ "$failures" -eq 0 ]; then
    echo "All statements were rejected as expected."
else
    echo "$failures statement(s) did not behave as expected."
    exit 1
fi
