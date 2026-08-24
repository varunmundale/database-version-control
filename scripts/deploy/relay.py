#!/usr/bin/env python3
"""Thin HTTP-to-TCP relay in front of a running dbService daemon.

dbgit's own wire protocol (DBGIT/1) is raw TCP, and a browser cannot open a raw TCP socket. This
relay is the bridge: it turns a JSON request into the header+command line DbGitClient itself would
send, and hands the exact response back. dbgit source is untouched, and the daemon still knows
nothing about any of this - see CommandContext/RequestHeader, rebuilt fresh per request, no per-user
state in the daemon ever.

The relay itself, though, *is* now stateful, on purpose - split into two very different pieces of
state (see workspace_store.WorkspaceStore for why): `main`'s tracked connection is a *singleton*,
initialized once and shared by every author (that's what `InitCommand`/`BranchConnections` actually
model - there's no such thing as "an author's own main"), while which branch an author currently has
checked out is per-author, the same thing ClientWorkspace's `.dbgit/HEAD` tracks per local directory
for the real CLI. That means a browser session no longer resends db-host/db-port/... on every call,
and the UI can force a first-time visitor through `/init` once, globally, rather than every author
separately.

POST /run/<author> with a JSON body:
    {"command": "dbgit log", "body": null}
    {"command": "dbgit add", "body": "CREATE TABLE t (id INT);"}

`author` is mandatory and lives in the URL, not the body - see the module docstring above. `body` is
only meaningful when `command` is `dbgit add` - see Main.run in the Java client: every other command
is one line, `add` is followed by the raw DDL until the client closes its half of the socket.

If this author hasn't been seen before, their branch defaults to "main" - "author not present ->
create workspace." Nothing is blocked preemptively for main lacking a tracked connection: it
(BranchConnections.forBranch) is only ever needed by a command that touches main itself - every other
branch is a scratchpad fork addressed by name, no credentials required. So a command run before
`/init` has ever been called goes through exactly as it would from the real CLI, and only if the
daemon itself refuses it for main not tracking a database does the relay append a nudge line pointing
at `/init/<author>` - the UI is what actually forces this up front (see index.html), by refusing to
show the command runner until GET /workspace says `initialized`. A `dbgit checkout ...` that succeeds
updates the caller's stored branch, same as `.dbgit/HEAD` would.

POST /init/<author> with an optional JSON body:
    {"db": {"host": "...", "port": 5432, "database": "...", "user": "...", "password": "..."}}

Runs `dbgit init`, pointing the one shared `main` at this connection - for every author, not just the
caller. Any field left out of `db` (or `db` omitted entirely) falls back to whatever main already
tracks, or DEFAULT_DB if nothing does yet. On success that connection is stored as main_db and from
then on every author's GET /workspace reports `initialized: true` immediately, with no init of their
own required - "initialized exactly once."

GET /workspace/<author> returns {"ok": true, "workspace": {...}} - the same shape /run and /init
responses carry, for a UI to poll on its own (e.g. right after the author field changes). This also
creates the author's branch record if it didn't exist yet, same as /run does; it never creates main_db.

Response shape for /run and /init: {"ok": true/false, "lines": [...], "workspace": {...}} - "ok"
mirrors the OK/ERR status line DbGitCommandListener writes (or is false without ever reaching it, for
the "not initialized" nudge); "lines" is everything after it, split the same way DbGitClient prints to
stdout/stderr; "workspace" is {"author", "branch", "initialized", "db"} with the password stripped out
of "db" before it goes back over the wire to a browser.

POST /admin/run runs one server-side script, from a fixed allowlist below - NOT an arbitrary shell
command. A public IP plus a literal "run whatever string the client sends" endpoint is a remote-code-
execution hole; this is the version of "run a script on the server from the client" that doesn't open
one. Body: {"name": "clear-everything"}. `name` must be a key in ADMIN_SCRIPTS - there is no way to
pass a path or arguments from the client, so nothing beyond what is listed below can ever run. A
successful `clear-everything` also resets WORKSPACES (main_db forgotten, every author's branch back to
"main") - see WorkspaceStore.clear()'s docstring for why: that script TRUNCATEs tracked_databases and
branch_metadata on the daemon side regardless of `--tracked`, so this relay's cache of them would
otherwise go stale the moment it runs.
Unauthenticated by design (no token) - the allowlist is the only guard. Anyone who can reach this
port can run anything in ADMIN_SCRIPTS, so keep that list short and keep this off the open internet
unless you mean it. The same is true of every endpoint above: there is no login, so anyone who can
reach this port can act as any author and read back main's stored (unhashed) db-user - see
workspace_store.describe_workspace for why the db *password* specifically never round-trips back out.

Run standalone: python3 relay.py [--relay-port 8080] [--dbgit-host 127.0.0.1] [--dbgit-port 47615]
                                  [--web-dir web] [--repo-dir ..] [--state-file /etc/dbgit/web-workspaces.json]
No third-party dependencies - only the standard library, so nothing to install on the VM.
"""
import argparse
import json
import re
import socket
import subprocess
import mimetypes
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from workspace_store import DEFAULT_DB, WorkspaceStore, db_from_payload, default_state_path, describe_workspace

ARGS = None  # set in main()
WORKSPACES = None  # WorkspaceStore, set in main()

# name the client sends -> (script path relative to --repo-dir, fixed argv - never client-supplied).
# Add an entry here to expose another script; nothing else changes. Every script is run with a
# timeout and its own working directory set to the repo root, exactly as if you'd SSH'd in and run it.
ADMIN_SCRIPTS = {
    "clear-everything": (["scripts/clear-everything.sh", "-y"], 120),
}

CHECKOUT_TARGET = re.compile(r"^dbgit\s+checkout\s+(?:-b\s+)?(\S+)", re.IGNORECASE)
INIT_COMMAND = re.compile(r"^dbgit\s+init\b", re.IGNORECASE)


def build_header(author: str, branch: str, db: dict | None) -> str:
    fields = [("author", author), ("branch", branch)]
    if db:
        fields += [("db-host", db["host"]), ("db-port", str(db["port"])), ("db-database", db["database"]),
                   ("db-user", db["user"]), ("db-password", db["password"])]
    return "DBGIT/1 " + " ".join(f"{k}={urllib.parse.quote(str(v), safe='')}" for k, v in fields)


def send_to_dbgit(header: str, command: str, body: str | None) -> tuple[bool, list[str]]:
    """Opens one TCP connection to dbService, writes header+command(+body), reads OK/ERR and the lines after it."""
    with socket.create_connection((ARGS.dbgit_host, ARGS.dbgit_port), timeout=30) as sock:
        writer = sock.makefile("w", encoding="utf-8", newline="\n")
        writer.write(header.rstrip("\n") + "\n")
        writer.write(command.rstrip("\n") + "\n")
        if body is not None:
            writer.write(body)
        writer.flush()
        sock.shutdown(socket.SHUT_WR)  # tells dbService "nothing more is coming" - what add's body reads until

        reader = sock.makefile("r", encoding="utf-8", newline="\n")
        status = reader.readline().rstrip("\n")
        lines = [line.rstrip("\n") for line in reader]
        return status == "OK", lines


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, format, *args):  # keep stdout readable under systemd; still logs via stderr default
        pass

    def _json(self, status: int, payload: dict):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/run/"):
            self._handle_run(urllib.parse.unquote(path[len("/run/"):]))
        elif path.startswith("/init/"):
            self._handle_init(urllib.parse.unquote(path[len("/init/"):]))
        elif path == "/admin/run":
            self._handle_admin_run()
        else:
            self._json(404, {"ok": False, "lines": ["No such endpoint: " + path]})

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def _handle_run(self, author: str):
        author = author.strip()
        if not author:
            self._json(400, {"ok": False, "lines": ["author is required in the URL: POST /run/<author>"]})
            return
        try:
            payload = self._read_json()
        except Exception as exception:
            self._json(400, {"ok": False, "lines": [f"bad request: {exception}"]})
            return
        command = (payload.get("command") or "").strip()
        body = payload.get("body")
        if not command:
            self._json(400, {"ok": False, "lines": ["command must not be blank"]})
            return

        branch = WORKSPACES.branch_for(author)
        main_db = WORKSPACES.main_db()
        initializing = INIT_COMMAND.match(command) is not None

        # Only main's tracked connection ever needs db-* - every other branch is a scratchpad fork
        # addressed by name (BranchConnections.forBranch). So there is nothing to gate here for an
        # author working entirely on a feature branch; sending db-* along anyway when we have it is
        # harmless (unused unless the branch in play is main). The nudge below is reactive, not
        # preemptive, precisely because "not initialized" only actually matters once a command tries to
        # touch main's live database - which the daemon itself is what knows.
        db = db_from_payload(payload, main_db) if initializing else main_db
        header = build_header(author, "main" if initializing else branch, db)
        self._run_and_respond(header, command, body, author, branch, initializing)

    def _handle_init(self, author: str):
        author = author.strip()
        if not author:
            self._json(400, {"ok": False, "lines": ["author is required in the URL: POST /init/<author>"]})
            return
        try:
            payload = self._read_json()
        except Exception as exception:
            self._json(400, {"ok": False, "lines": [f"bad request: {exception}"]})
            return

        branch = WORKSPACES.branch_for(author)
        db = db_from_payload(payload, WORKSPACES.main_db())
        header = build_header(author, "main", db)
        self._run_and_respond(header, "dbgit init", None, author, branch, initializing=True)

    def _run_and_respond(self, header: str, command: str, body, author: str, branch: str, initializing: bool):
        try:
            ok, lines = send_to_dbgit(header, command, body)
        except ConnectionRefusedError:
            self._json(502, {"ok": False, "lines": ["dbService is not reachable at "
                                                      f"{ARGS.dbgit_host}:{ARGS.dbgit_port}"]})
            return
        except Exception as exception:  # the relay's own job is just to report this back, not to crash
            self._json(500, {"ok": False, "lines": [f"relay error: {exception}"]})
            return

        if ok:
            if initializing:
                db = None
                for pair in header.split():
                    if pair.startswith("db-"):
                        db = db or {}
                        db[pair.split("=", 1)[0][3:]] = urllib.parse.unquote(pair.split("=", 1)[1])
                if db:
                    db["port"] = int(db["port"])
                WORKSPACES.set_main_db(db)
            else:
                target = CHECKOUT_TARGET.match(command)
                if target:
                    branch = target.group(1)
                    WORKSPACES.set_branch(author, branch)
        elif WORKSPACES.main_db() is None and any("is not tracking a database yet" in line for line in lines):
            # The one error BranchConnections.forBranch actually raises for this - see its docstring.
            # Reactive, not preemptive: this only fires for a command that truly needed main's tracked
            # connection, never for one that was happily working on a feature branch.
            lines = lines + [f"Hint: POST /init/{urllib.parse.quote(author, safe='')} to fix this - "
                              f"defaults to {DEFAULT_DB['host']}:{DEFAULT_DB['port']}/{DEFAULT_DB['database']} "
                              "if you don't send your own db details."]

        self._json(200, {"ok": ok, "lines": lines,
                          "workspace": describe_workspace(author, branch, WORKSPACES.main_db())})

    def _handle_admin_run(self):
        """Runs one server-side script from ADMIN_SCRIPTS - see the module docstring for why this is
        an allowlist keyed by name rather than anything the client can turn into an arbitrary command.
        No token check: the allowlist itself is the only guard here, on purpose - see the docstring."""
        try:
            payload = self._read_json()
            name = payload.get("name") or ""
        except Exception as exception:
            self._json(400, {"ok": False, "lines": [f"bad request: {exception}"]})
            return
        if name not in ADMIN_SCRIPTS:
            self._json(400, {"ok": False, "lines": [f"unknown script '{name}'. Known: "
                                                      + ", ".join(sorted(ADMIN_SCRIPTS))]})
            return
        argv, timeout = ADMIN_SCRIPTS[name]
        try:
            result = subprocess.run(argv, cwd=ARGS.repo_dir, capture_output=True, text=True,
                                     timeout=timeout)
            lines = (result.stdout + result.stderr).splitlines()
            ok = result.returncode == 0
            if ok and name == "clear-everything":
                # clear-everything.sh TRUNCATEs tracked_databases and resets branch_metadata to just
                # 'main' on the daemon side every run - see WorkspaceStore.clear()'s docstring for why
                # this cache has to be reset in lockstep rather than going stale.
                WORKSPACES.clear()
            self._json(200, {"ok": ok, "lines": lines})
        except subprocess.TimeoutExpired:
            self._json(504, {"ok": False, "lines": [f"'{name}' did not finish within {timeout}s"]})
        except Exception as exception:
            self._json(500, {"ok": False, "lines": [f"relay error running '{name}': {exception}"]})

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path.startswith("/workspace/"):
            self._handle_workspace_get(urllib.parse.unquote(path[len("/workspace/"):]))
            return
        # Serves the static web client, if configured - so one process is the whole "app".
        if not ARGS.web_dir:
            self._json(404, {"ok": False, "lines": ["no web client configured; POST /run/<author> directly"]})
            return
        relative = "index.html" if path in ("", "/") else path.lstrip("/")
        target = (Path(ARGS.web_dir) / relative).resolve()
        if not str(target).startswith(str(Path(ARGS.web_dir).resolve())) or not target.is_file():
            self._json(404, {"ok": False, "lines": ["not found: " + path]})
            return
        content_type = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _handle_workspace_get(self, author: str):
        author = author.strip()
        if not author:
            self._json(400, {"ok": False, "lines": ["author is required in the URL: GET /workspace/<author>"]})
            return
        branch = WORKSPACES.branch_for(author)
        self._json(200, {"ok": True, "lines": [], "workspace": describe_workspace(author, branch, WORKSPACES.main_db())})


def restart_daemon(service: str):
    """Every start of the relay forces a fresh dbService too, by design: a relay restart is what
    deploying a rebuilt daemon is supposed to trigger. Best-effort - if systemctl or sudo isn't there
    (e.g. running relay.py standalone on a machine with no systemd), this only warns and the relay
    still comes up, talking to whatever dbService is already running, if anything."""
    try:
        result = subprocess.run(["sudo", "systemctl", "restart", service],
                                 capture_output=True, text=True, timeout=30)
        if result.returncode == 0:
            print(f"Restarted {service}.")
        else:
            print(f"Could not restart {service} (continuing anyway): "
                  f"{(result.stderr or result.stdout).strip()}")
    except Exception as exception:
        print(f"Could not restart {service} (continuing anyway): {exception}")


def main():
    global ARGS, WORKSPACES
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-port", type=int, default=8080, help="port this relay listens on (the public one)")
    parser.add_argument("--dbgit-host", default="127.0.0.1", help="where dbService is listening")
    parser.add_argument("--dbgit-port", type=int, default=47615, help="dbService's port (dbgit.json service.port)")
    parser.add_argument("--web-dir", default=None, help="directory to serve the static client from, e.g. web/")
    parser.add_argument("--repo-dir", default=str(Path(__file__).resolve().parents[2]),
                         help="working directory ADMIN_SCRIPTS run from; defaults to this repo's root")
    parser.add_argument("--state-file", default=None,
                         help="where relay state (main's tracked db, INCLUDING the password, plus each "
                              "author's current branch) is persisted as JSON; defaults to "
                              "/etc/dbgit/web-workspaces.json, falling back to "
                              "<repo-dir>/.dbgit/web-workspaces.json if that isn't writable - see "
                              "workspace_store.default_state_path()")
    parser.add_argument("--daemon-service", default="dbgit-daemon.service",
                         help="systemd unit restarted every time the relay starts")
    parser.add_argument("--no-restart-daemon", action="store_true",
                         help="skip the dbService restart on startup (e.g. for local, non-systemd runs)")
    ARGS = parser.parse_args()

    if not ARGS.no_restart_daemon:
        restart_daemon(ARGS.daemon_service)

    state_path = Path(ARGS.state_file) if ARGS.state_file else default_state_path(Path(ARGS.repo_dir))
    WORKSPACES = WorkspaceStore(state_path)

    server = ThreadingHTTPServer(("0.0.0.0", ARGS.relay_port), Handler)
    print(f"Relay listening on :{ARGS.relay_port}, forwarding to dbService at "
          f"{ARGS.dbgit_host}:{ARGS.dbgit_port}" + (f", serving {ARGS.web_dir}" if ARGS.web_dir else "")
          + f", workspace state at {state_path}")
    server.serve_forever()


if __name__ == "__main__":
    main()
