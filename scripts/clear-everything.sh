#!/usr/bin/env bash
# Puts dbgit back to the state it is in before anything has ever been run: no branches, no commits,
# no changesets, no forked databases, no local workspace. Clears, in order: every branch database in
# the scratchpad container, the metadata store's four tables (truncated with RESTART IDENTITY, so the
# next commit is #1 again), and this workspace's .dbgit/. The containers themselves are never
# stopped. The database 'main' tracks is left alone unless --tracked is passed - dbgit did not create
# it, the same reason 'dbgit reset' refuses main.
#
# Tables are truncated rather than the metadata database dropped: the daemon creates that database
# once per process, so dropping it out from under a running ./dbService would leave it pointed at
# nothing. Don't run this while a dbgit command is in flight.
#
# Needs Docker. psql is not needed on the host: every statement runs in a throwaway container off
# the same image dbgit.json already names for the scratchpad.
set -euo pipefail
cd "$(dirname "$0")/.."

CONFIG="src/main/resources/dbgit.json"
assume_yes=false
clear_tracked=false

usage() {
    cat <<'USAGE'
Usage: ./scripts/clear-everything.sh [options]

  -y, --yes        don't ask for confirmation
      --tracked    also empty the database main tracks: DROP SCHEMA public CASCADE, then recreate
                   it. This is a real database dbgit never created - off by default for that reason
  -h, --help       print this
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        -y|--yes) assume_yes=true ;;
        --tracked) clear_tracked=true ;;
        -h|--help) usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; usage >&2; exit 64 ;;
    esac
    shift
done

command -v docker >/dev/null || { echo "docker is not on PATH, and every step here needs it." >&2; exit 2; }
command -v python3 >/dev/null || { echo "python3 is not on PATH; it is what reads $CONFIG." >&2; exit 2; }

# dbgit.json is the single source of truth for all of this - reading it here is what keeps the
# script honest when a port or a container name changes.
eval "$(python3 - "$CONFIG" <<'PY'
import json, shlex, sys

config = json.load(open(sys.argv[1]))
metadata = config["metadata"]
branches = config["branchDatabases"]
values = {
    "SERVICE_PORT": config.get("service", {}).get("port", 0),
    "META_HOST": metadata["host"], "META_PORT": metadata["port"],
    "META_USER": metadata["user"], "META_PASSWORD": metadata.get("password", ""),
    "META_ADMIN_DB": metadata.get("adminDatabase", "postgres"), "META_DB": metadata["database"],
    "BRANCH_CONTAINER": branches["containerName"], "BRANCH_IMAGE": branches["image"],
    "BRANCH_PORT": branches["hostPort"], "BRANCH_USER": branches["user"],
    "BRANCH_PASSWORD": branches.get("password", ""),
    "BRANCH_ADMIN_DB": branches.get("adminDatabase", "postgres"),
    "BRANCH_DIALECT": branches.get("dialect", "postgresql"),
}
for name, value in values.items():
    print(f"{name}={shlex.quote(str(value))}")
PY
)"

# One psql, wherever the server is: --network host is what makes the 'localhost' in dbgit.json mean
# the same thing inside the container as it does to the daemon.
psql_at() {
    local host=$1 port=$2 user=$3 password=$4 database=$5 sql=$6
    docker run --rm --network host -e "PGPASSWORD=$password" "$BRANCH_IMAGE" \
        psql -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$user" -d "$database" -tAc "$sql"
}

reachable() {
    psql_at "$1" "$2" "$3" "$4" "$5" "SELECT 1" >/dev/null 2>&1
}

daemon_is_running() {
    [ "$SERVICE_PORT" != "0" ] && (exec 3<>"/dev/tcp/127.0.0.1/$SERVICE_PORT") 2>/dev/null
}

# Read before .dbgit/ is deleted: the password for the tracked database is written nowhere else.
tracked_field() {
    python3 - "$1" <<'PY' 2>/dev/null || true
import json, sys
try:
    main = json.load(open(".dbgit/config.json"))["branches"]["main"]
except Exception:
    sys.exit(0)
print(main.get(sys.argv[1], ""))
PY
}

TRACKED_HOST="$(tracked_field host)"
TRACKED_PORT="$(tracked_field port)"
TRACKED_DB="$(tracked_field database)"
TRACKED_USER="$(tracked_field user)"
TRACKED_PASSWORD="$(tracked_field password)"

echo "This will delete:"
echo "  - every branch database in '$BRANCH_CONTAINER' (localhost:$BRANCH_PORT) - the container stays up"
echo "  - every branch, commit and changeset in $META_DB on $META_HOST:$META_PORT"
echo "  - .dbgit/ in $(pwd)"
if [ "$clear_tracked" = true ]; then
    if [ -n "$TRACKED_DB" ]; then
        echo "  - EVERY TABLE in '$TRACKED_DB' on $TRACKED_HOST:$TRACKED_PORT - the database main tracks"
    else
        echo "  - (--tracked given, but this workspace has not run 'dbgit init', so there is nothing to empty)"
    fi
else
    echo "Left alone: the database main tracks. Pass --tracked to empty that too."
fi

echo
echo "=== 1/4 Branch databases ==="
if [ "$BRANCH_DIALECT" != "postgresql" ]; then
    # h2 branch databases live in the daemon's own memory and mysql needs a different client; in
    # both cases there is nothing here to connect to and drop.
    echo "branchDatabases.dialect is '$BRANCH_DIALECT', not postgresql - skipping."
    if [ "$BRANCH_DIALECT" = "h2" ]; then
        echo "H2 branch databases are in the daemon's memory: restart ./dbService to clear them."
    fi
elif ! reachable "localhost" "$BRANCH_PORT" "$BRANCH_USER" "$BRANCH_PASSWORD" "$BRANCH_ADMIN_DB"; then
    echo "Nothing listening on localhost:$BRANCH_PORT - no branch databases to drop."
else
    # Everything in the scratchpad is dbgit's; the admin database is the one it connects through.
    databases="$(psql_at localhost "$BRANCH_PORT" "$BRANCH_USER" "$BRANCH_PASSWORD" "$BRANCH_ADMIN_DB" \
        "SELECT datname FROM pg_database WHERE NOT datistemplate AND datname <> '$BRANCH_ADMIN_DB' ORDER BY datname")"
    if [ -z "$databases" ]; then
        echo "No branch databases to drop."
    else
        while IFS= read -r database; do
            [ -n "$database" ] || continue
            # FORCE disconnects whoever is still attached - a daemon holding a connection open would
            # otherwise make this fail rather than wait.
            psql_at localhost "$BRANCH_PORT" "$BRANCH_USER" "$BRANCH_PASSWORD" "$BRANCH_ADMIN_DB" \
                "DROP DATABASE IF EXISTS \"$database\" WITH (FORCE)" >/dev/null
            echo "Dropped $database"
        done <<<"$databases"
    fi
fi

echo
echo "=== 2/4 Metadata store ==="
if ! reachable "$META_HOST" "$META_PORT" "$META_USER" "$META_PASSWORD" "$META_ADMIN_DB"; then
    echo "Cannot reach the metadata server at $META_HOST:$META_PORT - skipping."
elif [ -z "$(psql_at "$META_HOST" "$META_PORT" "$META_USER" "$META_PASSWORD" "$META_ADMIN_DB" \
        "SELECT 1 FROM pg_database WHERE datname = '$META_DB'")" ]; then
    echo "No '$META_DB' database yet - nothing to clear."
else
    # One TRUNCATE for all four tables: CASCADE would reach the rest through their foreign keys
    # anyway, but naming them says what is being emptied. RESTART IDENTITY is what makes the next
    # commit '#1' again, so a demo's output reads the same on every run.
    psql_at "$META_HOST" "$META_PORT" "$META_USER" "$META_PASSWORD" "$META_DB" \
        "TRUNCATE branch_commits, branch_metadata, branch_changesets, tracked_databases RESTART IDENTITY CASCADE;
         INSERT INTO branch_metadata (branch_name, forked_from) VALUES ('main', NULL);" >/dev/null
    echo "Emptied branch_metadata, branch_commits, branch_changesets and tracked_databases in $META_DB."
    echo "'main' is back, tracking nothing - as after a first bootstrap."
fi

echo
echo "=== 3/4 This workspace ==="
if [ -d .dbgit ]; then
    rm -rf .dbgit/
    echo "Removed $(pwd)/.dbgit - HEAD is back to 'main' and 'dbgit init' has to be run again."
else
    echo "No .dbgit here."
fi
echo "Any other directory you have run dbgit from keeps its own .dbgit; this only clears this one."

echo
echo "=== 4/4 The database main tracks ==="
if [ "$clear_tracked" != true ]; then
    echo "Left alone (pass --tracked to empty it)."
elif [ -z "$TRACKED_DB" ]; then
    echo "This workspace never ran 'dbgit init', so it named no tracked database."
elif ! reachable "$TRACKED_HOST" "$TRACKED_PORT" "$TRACKED_USER" "$TRACKED_PASSWORD" "$TRACKED_DB"; then
    echo "Cannot reach '$TRACKED_DB' at $TRACKED_HOST:$TRACKED_PORT - skipping."
else
    psql_at "$TRACKED_HOST" "$TRACKED_PORT" "$TRACKED_USER" "$TRACKED_PASSWORD" "$TRACKED_DB" \
        "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
    echo "Emptied schema 'public' in '$TRACKED_DB' - every table, constraint and index in it is gone."
fi

echo
echo "Done. dbgit is back to a first run."
if daemon_is_running; then
    echo "The daemon on port $SERVICE_PORT and the containers can all stay up: the metadata tables were"
    echo "emptied rather than dropped, and nothing that was running was stopped."
else
    echo "Start the daemon with ./dbService when you want to run a demo."
fi
