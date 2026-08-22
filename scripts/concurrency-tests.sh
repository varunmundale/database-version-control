#!/usr/bin/env bash
# Runs every concurrency test script in turn and reports which ones failed.
#
# Each script is independently runnable - use them singly when chasing one capability. They all need ./dbService
# running and Docker available, and none of them touches `main`, so no `dbgit init` is required.
set -uo pipefail
cd "$(dirname "$0")"

SCRIPTS=(
    concurrency-isolation-test.sh
    concurrency-serialization-test.sh
    concurrency-commit-race-test.sh
    concurrency-recovery-test.sh
)

failed=()
for script in "${SCRIPTS[@]}"; do
    printf '\n########## %s ##########\n' "$script"
    if ! "./$script"; then
        failed+=("$script")
    fi
done

printf '\n==================================\n'
if [ "${#failed[@]}" -eq 0 ]; then
    printf 'All %d concurrency test scripts passed.\n' "${#SCRIPTS[@]}"
else
    printf 'Failed: %s\n' "${failed[*]}"
    exit 1
fi
