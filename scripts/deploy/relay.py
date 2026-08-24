#!/usr/bin/env python3
"""Thin HTTP-to-TCP relay in front of a running dbService daemon.

dbgit's own wire protocol (DBGIT/1) is raw TCP, and a browser cannot open a raw TCP socket. This
relay is the smallest possible bridge: it does not know anything about branches, commits or DDL - it
takes the exact bytes a request POSTs, sends them to dbService over a socket exactly the way
DbGitClient does, and hands the exact response back. No session, no state, no dbgit source touched.

POST /run with a JSON body:
    {"header": "DBGIT/1 author=... branch=...", "command": "dbgit log", "body": null}
    {"header": "DBGIT/1 author=... branch=... db-host=...", "command": "dbgit add", "body": "CREATE TABLE t (id INT);"}

`body` is only meaningful when `command` is `dbgit add` - see Main.run in the Java client: every other
command is one line, `add` is followed by the raw DDL until the client closes its half of the socket.

Response: {"ok": true/false, "lines": [...]} - "ok" mirrors the OK/ERR status line DbGitCommandListener
writes; "lines" is everything after it, split the same way DbGitClient prints to stdout/stderr.

POST /admin/run runs one server-side script, from a fixed allowlist below - NOT an arbitrary shell
command. A public IP plus a literal "run whatever string the client sends" endpoint is a remote-code-
execution hole; this is the version of "run a script on the server from the client" that doesn't open
one. Body: {"name": "clear-everything"}. `name` must be a key in ADMIN_SCRIPTS - there is no way to
pass a path or arguments from the client, so nothing beyond what is listed below can ever run.
Unauthenticated by design (no token) - the allowlist is the only guard. Anyone who can reach this
port can run anything in ADMIN_SCRIPTS, so keep that list short and keep this off the open internet
unless you mean it.

Run standalone: python3 relay.py [--relay-port 8080] [--dbgit-host 127.0.0.1] [--dbgit-port 47615]
                                  [--web-dir web] [--repo-dir ..]
No third-party dependencies - only the standard library, so nothing to install on the VM.
"""
import argparse
import json
import socket
import subprocess
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ARGS = None  # set in main()

# name the client sends -> (script path relative to --repo-dir, fixed argv - never client-supplied).
# Add an entry here to expose another script; nothing else changes. Every script is run with a
# timeout and its own working directory set to the repo root, exactly as if you'd SSH'd in and run it.
ADMIN_SCRIPTS = {
    "clear-everything": (["scripts/clear-everything.sh", "-y"], 120),
}


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
        self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_POST(self):
        if self.path == "/run":
            self._handle_run()
        elif self.path == "/admin/run":
            self._handle_admin_run()
        else:
            self._json(404, {"ok": False, "lines": ["No such endpoint: " + self.path]})

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0"))
        return json.loads(self.rfile.read(length) or b"{}")

    def _handle_run(self):
        try:
            payload = self._read_json()
            header = payload.get("header") or ""
            command = payload.get("command") or ""
            body = payload.get("body")
            if not header.startswith("DBGIT/1"):
                self._json(400, {"ok": False, "lines": ["header must start with DBGIT/1"]})
                return
            if not command.strip():
                self._json(400, {"ok": False, "lines": ["command must not be blank"]})
                return
            ok, lines = send_to_dbgit(header, command, body)
            self._json(200, {"ok": ok, "lines": lines})
        except ConnectionRefusedError:
            self._json(502, {"ok": False, "lines": ["dbService is not reachable at "
                                                      f"{ARGS.dbgit_host}:{ARGS.dbgit_port}"]})
        except Exception as exception:  # the relay's own job is just to report this back, not to crash
            self._json(500, {"ok": False, "lines": [f"relay error: {exception}"]})

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
            self._json(200, {"ok": result.returncode == 0, "lines": lines})
        except subprocess.TimeoutExpired:
            self._json(504, {"ok": False, "lines": [f"'{name}' did not finish within {timeout}s"]})
        except Exception as exception:
            self._json(500, {"ok": False, "lines": [f"relay error running '{name}': {exception}"]})

    def do_GET(self):
        # Serves the static web client, if configured - so one process is the whole "app".
        if not ARGS.web_dir:
            self._json(404, {"ok": False, "lines": ["no web client configured; POST /run directly"]})
            return
        relative = "index.html" if self.path in ("", "/") else self.path.lstrip("/")
        target = (Path(ARGS.web_dir) / relative).resolve()
        if not str(target).startswith(str(Path(ARGS.web_dir).resolve())) or not target.is_file():
            self._json(404, {"ok": False, "lines": ["not found: " + self.path]})
            return
        content_type = mimetypes.guess_type(str(target))[0] or "application/octet-stream"
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


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
    global ARGS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--relay-port", type=int, default=8080, help="port this relay listens on (the public one)")
    parser.add_argument("--dbgit-host", default="127.0.0.1", help="where dbService is listening")
    parser.add_argument("--dbgit-port", type=int, default=47615, help="dbService's port (dbgit.json service.port)")
    parser.add_argument("--web-dir", default=None, help="directory to serve the static client from, e.g. web/")
    parser.add_argument("--repo-dir", default=str(Path(__file__).resolve().parents[2]),
                         help="working directory ADMIN_SCRIPTS run from; defaults to this repo's root")
    parser.add_argument("--daemon-service", default="dbgit-daemon.service",
                         help="systemd unit restarted every time the relay starts")
    parser.add_argument("--no-restart-daemon", action="store_true",
                         help="skip the dbService restart on startup (e.g. for local, non-systemd runs)")
    ARGS = parser.parse_args()

    if not ARGS.no_restart_daemon:
        restart_daemon(ARGS.daemon_service)

    server = ThreadingHTTPServer(("0.0.0.0", ARGS.relay_port), Handler)
    print(f"Relay listening on :{ARGS.relay_port}, forwarding to dbService at "
          f"{ARGS.dbgit_host}:{ARGS.dbgit_port}" + (f", serving {ARGS.web_dir}" if ARGS.web_dir else ""))
    server.serve_forever()


if __name__ == "__main__":
    main()
