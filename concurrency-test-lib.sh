#!/usr/bin/env bash
# Shared helpers for the concurrency test scripts. Sourced, not run.
#
# These drive the real ./dbgit client against a running ./dbService, so they exercise the whole stack: the wire
# protocol, the request header, the thread pool and the branch locks. They deliberately never touch `main`, so no
# `dbgit init` and no tracked database is needed - every branch they use is a fork in the scratchpad container.
#
# Requirements: ./dbService running, and Docker available for the shared PostgreSQL container.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DBGIT="$ROOT/dbgit"
RUN_ID="$$"
WORKDIR="$(mktemp -d)"
PASSED=0
FAILED=0

trap 'rm -rf "$WORKDIR"' EXIT

# --- running the client -------------------------------------------------------------------------------------

# workspace, args... - each workspace keeps its own .dbgit/HEAD, which is what makes callers independent.
dbgit_in() {
    local workspace="$1"; shift
    DBGIT_WORKSPACE="$workspace" "$DBGIT" "$@"
}

# workspace, ddl - `dbgit add` takes its statement on stdin, since DDL can span lines.
dbgit_add_in() {
    DBGIT_WORKSPACE="$1" "$DBGIT" add <<<"$2"
}

new_workspace() {
    local workspace="$WORKDIR/$1"
    mkdir -p "$workspace"
    printf '%s' "$workspace"
}

branch_name() {
    printf 'conc-%s/%s' "$RUN_ID" "$1"
}

# --- reading dbgit output -----------------------------------------------------------------------------------

# The count from "Working set (N uncommitted changeset(s)):", or 0 when the log says the working set is clean.
working_set_size() {
    local log="$1"
    if grep -q 'Working set: clean' <<<"$log"; then
        printf '0'
        return
    fi
    sed -n 's/^Working set (\([0-9]*\) uncommitted.*/\1/p' <<<"$log" | head -1
}

# Every changeset id the log lists under a commit, one per line - what a replay would actually walk. Working-set
# entries carry a [STATUS] marker after the id, which is what keeps them out of this.
committed_changeset_ids() {
    sed -n 's/^  #\([0-9]*\) \([^[].*\)/\1/p' <<<"$1"
}

# --- assertions ---------------------------------------------------------------------------------------------

ok() {
    PASSED=$((PASSED + 1))
    printf '  ok    %s\n' "$1"
}

bad() {
    FAILED=$((FAILED + 1))
    printf '  FAIL  %s\n' "$1"
    shift
    local detail
    for detail in "$@"; do
        printf '        %s\n' "$detail"
    done
}

assert_eq() {
    if [ "$2" = "$3" ]; then ok "$1"; else bad "$1" "expected: $3" "actual:   $2"; fi
}

assert_contains() {
    if grep -qF -- "$3" <<<"$2"; then ok "$1"; else bad "$1" "expected to contain: $3" "actual: $2"; fi
}

assert_not_contains() {
    if grep -qF -- "$3" <<<"$2"; then bad "$1" "expected NOT to contain: $3" "actual: $2"; else ok "$1"; fi
}

require_daemon() {
    if ! dbgit_in "$(new_workspace probe)" branch >/dev/null 2>&1; then
        printf 'dbService is not reachable. Start it first, in its own terminal:\n\n    ./dbService\n\n' >&2
        exit 2
    fi
}

heading() {
    printf '\n== %s ==\n' "$1"
}

summary() {
    printf '\n%d passed, %d failed\n' "$PASSED" "$FAILED"
    [ "$FAILED" -eq 0 ] || exit 1
}
