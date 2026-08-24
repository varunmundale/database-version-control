#!/usr/bin/env bash
# The one command you run on a brand-new machine: gets dbgit onto it and hands off to setup.sh, which
# does everything else. No configuration is required.
#
#   curl -fsSL https://raw.githubusercontent.com/varunmundale/database-version-control/master/scripts/deploy/bootstrap.sh | bash
#
# Prefer not to pipe curl into bash? Same thing, inspectable first:
#   curl -fsSLO https://raw.githubusercontent.com/varunmundale/database-version-control/master/scripts/deploy/bootstrap.sh
#   less bootstrap.sh   # read it
#   bash bootstrap.sh
#
# Safe to re-run any time, including to deploy an update: if the clone already exists, this
# force-overwrites it to match the remote branch (git fetch + reset --hard + clean -fdx), discarding
# any local edits made directly on the machine.
#
#   ./bootstrap.sh clean   # tears down everything setup.sh created, THEN redeploys from scratch
set -euo pipefail

REPO_URL="${REPO_URL:-http://github.com/varunmundale/database-version-control.git}"
REPO_BRANCH="${REPO_BRANCH:-master}"
INSTALL_DIR="${INSTALL_DIR:-/opt/dbgit}"

command -v git >/dev/null || { sudo apt-get update -y && sudo apt-get install -y git; }

if [ "${1:-}" = "clean" ] && [ -x "$INSTALL_DIR/scripts/deploy/setup.sh" ]; then
    "$INSTALL_DIR/scripts/deploy/setup.sh" clean
fi

echo "=== dbgit at $INSTALL_DIR, tracking $REPO_URL#$REPO_BRANCH ==="
if [ -d "$INSTALL_DIR/.git" ]; then
    echo "already cloned - force-overwriting to the latest $REPO_BRANCH (git fetch + reset --hard)"
    sudo git -C "$INSTALL_DIR" fetch origin "$REPO_BRANCH"
    sudo git -C "$INSTALL_DIR" checkout "$REPO_BRANCH"
    sudo git -C "$INSTALL_DIR" reset --hard "origin/$REPO_BRANCH"
    sudo git -C "$INSTALL_DIR" clean -fdx
else
    sudo mkdir -p "$(dirname "$INSTALL_DIR")"
    sudo git clone --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi
sudo chown -R "$(whoami)" "$INSTALL_DIR"

exec "$INSTALL_DIR/scripts/deploy/setup.sh"
