#!/usr/bin/env bash
# Boots dbgit end to end, with zero required configuration: installs Docker if it isn't already
# there, brings up a metadata Postgres on :5432 and a "production" Postgres on :5433 (what `main`
# tracks) - reusing whatever is already listening on those ports rather than colliding with it, so
# this is safe to run on a machine that already has its own Postgres there - installs a JDK, builds
# the project, and installs two systemd services: dbService (the daemon) and the HTTP relay in front
# of it. Runs directly out of the directory it lives in; see bootstrap.sh for getting that directory
# onto a fresh machine via git in the first place.
#
# Deliberately does NOT write dbgit.json: the version already checked into the repo
# (src/main/resources/dbgit.json) already points at exactly localhost:5432/postgres:postgres for the
# metadata store and the postgresql dialect for branch databases (which is what needs the scratchpad
# container on :55432 - dbgit brings that up itself, the moment something forks a branch; see
# Forker/SharedContainer). Nothing here needs to be templated, so nothing is required as input.
#
#   ./scripts/deploy/setup.sh              # everything, from a clean machine or this one
#   ./scripts/deploy/setup.sh clean        # tears down every container/service/file this script made
#
# Every setting below has a default; only override one if you actually need to.
set -euo pipefail

SERVICE_PORT="${SERVICE_PORT:-47615}"     # dbService's own port - stays internal (127.0.0.1 only)
RELAY_PORT="${RELAY_PORT:-8080}"          # the one port you open in a VM's firewall
JDK_FEATURE_VERSION="${JDK_FEATURE_VERSION:-25}"
META_CONTAINER="${META_CONTAINER:-dbgit-metadata}"
META_PORT="${META_PORT:-5432}"
PROD_CONTAINER="${PROD_CONTAINER:-dbgit-production-db}"
PROD_PORT="${PROD_PORT:-5433}"
PG_IMAGE="${PG_IMAGE:-postgres:16-alpine}"

INSTALL_DIR="$(cd "$(dirname "$0")/../.." && pwd)"
JDK_DIR="/opt/temurin-${JDK_FEATURE_VERSION}"

port_is_listening() {
    (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null && exec 3>&- 3<&-
}

# Idempotent: reuses whatever already answers on $port (this machine's own Postgres, or a container
# from a previous run of this script), starts a stopped container of the same name, or creates one
# fresh - in that order, so re-running this never fights something already there for the port.
ensure_postgres() {
    local name="$1" port="$2"
    if port_is_listening "$port"; then
        echo "  :$port already has something listening - reusing it, not touching it."
        return
    fi
    if sudo docker ps -a --format '{{.Names}}' | grep -qx "$name"; then
        echo "  starting existing container '$name'"
        sudo docker start "$name" >/dev/null
    else
        echo "  creating '$name' ($PG_IMAGE) on :$port"
        sudo docker run -d --name "$name" --restart unless-stopped \
            -p "127.0.0.1:${port}:5432" -e POSTGRES_PASSWORD=postgres "$PG_IMAGE" >/dev/null
    fi
    echo -n "  waiting for :$port to accept connections"
    for _ in $(seq 1 30); do
        port_is_listening "$port" && { echo " - up"; return; }
        echo -n "."
        sleep 1
    done
    echo " - still not answering after 30s; check 'docker logs $name'" >&2
    exit 1
}

teardown() {
    echo "=== tearing down everything setup.sh created ==="
    sudo systemctl disable --now dbgit-relay.service 2>/dev/null || true
    sudo systemctl disable --now dbgit-daemon.service 2>/dev/null || true
    sudo rm -f /etc/systemd/system/dbgit-daemon.service /etc/systemd/system/dbgit-relay.service
    sudo systemctl daemon-reload 2>/dev/null || true
    for c in "$META_CONTAINER" "$PROD_CONTAINER"; do
        sudo docker rm -f "$c" >/dev/null 2>&1 && echo "removed container $c" || true
    done
    sudo rm -rf /etc/dbgit "$INSTALL_DIR/.dbgit" "$INSTALL_DIR/target"
    echo "Done. The scratchpad container (postgres-branches-scratchpad, if dbgit ever created one)"
    echo "and the JDK/Docker installs themselves are left alone - re-run without 'clean' to redeploy."
}

if [ "${1:-}" = "clean" ]; then
    teardown
    exit 0
fi

echo "=== 1/6: Docker ==="
if ! command -v docker >/dev/null; then
    sudo apt-get update -y
    sudo apt-get install -y docker.io
    sudo systemctl enable --now docker
else
    echo "already installed"
fi

echo
echo "=== 2/6: metadata Postgres (:$META_PORT) and production Postgres (:$PROD_PORT, what 'main' tracks) ==="
ensure_postgres "$META_CONTAINER" "$META_PORT"
ensure_postgres "$PROD_CONTAINER" "$PROD_PORT"

echo
echo "=== 3/6: swap (small free-tier VMs are short on RAM for a Maven build) ==="
if [ ! -f /swapfile ] && [ "$(free -m | awk '/^Mem:/{print $2}')" -lt 4000 ]; then
    sudo fallocate -l 2G /swapfile
    sudo chmod 600 /swapfile
    sudo mkswap /swapfile
    sudo swapon /swapfile
    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab >/dev/null
else
    echo "skipped (already present, or enough RAM without it)"
fi

echo
echo "=== 4/6: JDK ${JDK_FEATURE_VERSION} (Temurin - apt's own openjdk package is usually far behind) ==="
if [ ! -x "$JDK_DIR/bin/java" ]; then
    sudo apt-get update -y
    sudo apt-get install -y curl maven python3
    curl -fsSL -o /tmp/temurin.tar.gz \
        "https://api.adoptium.net/v3/binary/latest/${JDK_FEATURE_VERSION}/ga/linux/x64/jdk/hotspot/normal/eclipse"
    sudo mkdir -p "$JDK_DIR"
    sudo tar -xzf /tmp/temurin.tar.gz -C "$JDK_DIR" --strip-components=1
    rm /tmp/temurin.tar.gz
else
    echo "already present at $JDK_DIR"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

echo
echo "=== 5/6: build (compiles once; ./dbService and ./dbgit are mvn exec:java wrappers, unchanged) ==="
cd "$INSTALL_DIR"
mvn -q compile

echo
echo "=== 6/6: systemd units, then point main at the production database ==="
sudo tee /etc/systemd/system/dbgit-daemon.service >/dev/null <<UNIT
[Unit]
Description=dbgit daemon (dbService)
After=network.target docker.service
Requires=docker.service

[Service]
WorkingDirectory=${INSTALL_DIR}
Environment=JAVA_HOME=${JDK_DIR}
Environment=PATH=${JDK_DIR}/bin:/usr/bin:/bin
ExecStart=${INSTALL_DIR}/dbService
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo tee /etc/systemd/system/dbgit-relay.service >/dev/null <<UNIT
[Unit]
Description=dbgit HTTP relay (request/response bridge for the web client)
After=dbgit-daemon.service network.target

[Service]
WorkingDirectory=${INSTALL_DIR}
ExecStart=/usr/bin/python3 ${INSTALL_DIR}/scripts/deploy/relay.py \
    --relay-port ${RELAY_PORT} --dbgit-port ${SERVICE_PORT} \
    --web-dir ${INSTALL_DIR}/scripts/deploy/web \
    --repo-dir ${INSTALL_DIR} \
    --daemon-service dbgit-daemon.service
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
# dbgit-daemon is enabled for boot but deliberately not started/restarted here - starting the relay
# is what starts (or restarts) it, via relay.py's own restart_daemon() on every launch. One trigger,
# one place, instead of setup.sh and relay.py racing to both manage the same service.
sudo systemctl enable dbgit-daemon.service
sudo systemctl enable --now dbgit-relay.service
sudo systemctl restart dbgit-relay.service   # guarantees a fresh relay + a freshly (re)started daemon, even on redeploy

echo -n "waiting for dbService to accept connections on :$SERVICE_PORT"
for _ in $(seq 1 60); do
    port_is_listening "$SERVICE_PORT" && { echo " - up"; break; }
    echo -n "."
    sleep 1
done
# A cold ~/.m2 means dbService's own first launch (mvn exec:java resolving exec-maven-plugin, not
# just compiling) can take a while - up to a minute here, once, ever. Not fatal if it is still not up:
# the dbgit init call below simply fails harmlessly and can be re-run once it catches up.

# main has to track something before any command that touches it will work - point it at the
# production database this script just brought up, so a fresh deploy is immediately usable rather
# than erroring on step one. Idempotent: dbgit init just refreshes the connection if run again.
if ! "$INSTALL_DIR/dbgit" init --host localhost --port "$PROD_PORT" --database postgres \
        --user postgres --password postgres --author "setup" >/dev/null 2>&1; then
    echo "note: 'dbgit init' didn't go through yet (daemon still warming up) - run it yourself once" >&2
    echo "      it's ready: $INSTALL_DIR/dbgit init --host localhost --port $PROD_PORT --database postgres --user postgres --password postgres --author setup" >&2
fi

echo
echo "=== done ==="
echo "dbService  : sudo systemctl status dbgit-daemon   (journalctl -u dbgit-daemon -f to tail)"
echo "relay      : sudo systemctl status dbgit-relay    (journalctl -u dbgit-relay -f to tail)"
echo "web client : http://<this machine's address>:${RELAY_PORT}/"
echo "main tracks: localhost:${PROD_PORT}/postgres (the '${PROD_CONTAINER}' container)"
echo
echo "On GCP, :${RELAY_PORT} is closed by default - open it once per project/VM tag from a machine"
echo "with gcloud authenticated (not from here):"
echo "  gcloud compute firewall-rules create allow-dbgit --allow=tcp:${RELAY_PORT} --source-ranges=0.0.0.0/0"
echo "then either add the same network tag the rule targets to this VM, or add --target-tags=dbgit to"
echo "the command above and 'gcloud compute instances add-tags <vm-name> --tags=dbgit' to this one."
echo
echo "To redeploy after a change: re-run this script (or bootstrap.sh on a fresh machine)."
echo "To tear everything down: ./scripts/deploy/setup.sh clean"
