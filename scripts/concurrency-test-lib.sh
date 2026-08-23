#!/usr/bin/env bash
# Shared helpers for the concurrency test scripts. Sourced, not run.
#
# These drive the real ./dbgit client against a running ./dbService, so they exercise the whole stack: the wire
# protocol, the request header, the thread pool and the branch locks. Every branch they use is a fork in the
# scratchpad container, never `main` itself - but `dbgit init` is still how a workspace's author is set (the only
# way dbgit exposes one), so `new_workspace` points `main` at the same always-available scratchpad target every
# demo script uses, purely to give each simulated caller a distinct commit identity.
#
# Requirements: ./dbService running, and Docker available for the shared PostgreSQL container.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DBGIT="$REPO_ROOT/dbgit"
RUN_ID="$$"
WORKDIR="$(mktemp -d)"
PASSED=0
FAILED=0

# The scratchpad container's own admin database - not `main`'s real target in production, but reachable without
# any setup beyond what these tests already require, and every workspace pointing `main` at the same database is
# harmless: only `--author` differs between them.
INIT_HOST=localhost
INIT_PORT=55432
INIT_DATABASE=postgres
INIT_DB_USER=postgres
INIT_DB_PASSWORD=postgres

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

# A caller switched workspaces, so its commits should say so. `--author` only exists on `dbgit init`, so this is
# also where every concurrency-test workspace ends up pointing `main` at the scratchpad target - an incidental
# side effect nothing here reads back.
new_workspace() {
    local workspace="$WORKDIR/$1"
    mkdir -p "$workspace"
    dbgit_in "$workspace" init --host "$INIT_HOST" --port "$INIT_PORT" --database "$INIT_DATABASE" \
        --user "$INIT_DB_USER" --password "$INIT_DB_PASSWORD" --author "$1" >/dev/null
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
    # A plain `branch` needs only the daemon, not the scratchpad database `new_workspace`'s init also touches -
    # so this stays a pure reachability check, independent of whether that target is up yet.
    local probe="$WORKDIR/probe"
    mkdir -p "$probe"
    if ! dbgit_in "$probe" branch >/dev/null 2>&1; then
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
