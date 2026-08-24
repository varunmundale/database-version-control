#!/usr/bin/env python3
"""State for the dbgit web relay (see relay.py's module docstring for the wire protocol this feeds).

Two different things are tracked here, because they have two different shapes:

- `main`'s tracked connection is a *singleton*, shared by every author. `InitCommand`/`BranchConnections`
  operate on the one branch named "main" - there is no such thing as "an author's own main," any more
  than there is "an author's own commit graph." It gets set exactly once (dbgit init is idempotent, so
  re-running it is a no-op refresh, not a second database) and every author sees the same answer.
- which branch an author currently has checked out *is* per-author - the same thing ClientWorkspace's
  `.dbgit/HEAD` tracks per local directory for the real CLI, just keyed by author instead of by
  filesystem path, since a browser has no filesystem of its own to keep one in. Branches themselves
  still live in one shared graph (see CLAUDE.md) - this is only ever a personal pointer into it.
"""
import json
import os
import tempfile
from pathlib import Path

# What `dbgit init` runs against when nothing says otherwise - the same values the old static web form
# pre-filled, matching setup.sh's "production" Postgres on :5433.
DEFAULT_DB = {"host": "localhost", "port": 5433, "database": "postgres", "user": "postgres", "password": "postgres"}


def default_state_path(repo_dir: Path) -> Path:
    """/etc/dbgit is the deploy-owned config location (setup.sh's teardown already rm -rf's it), and
    outlives a bootstrap.sh redeploy's `git clean -fdx` the way anything under repo_dir would not.
    Falls back to inside the repo only for a local/dev run where /etc isn't writable, and says so."""
    preferred = Path("/etc/dbgit/web-workspaces.json")
    try:
        preferred.parent.mkdir(parents=True, exist_ok=True)
        probe = preferred.parent / ".write-test"
        probe.touch()
        probe.unlink()
        return preferred
    except OSError:
        fallback = repo_dir / ".dbgit" / "web-workspaces.json"
        print(f"warning: {preferred.parent} isn't writable here; falling back to {fallback}. That path "
              "is inside --repo-dir and will be wiped by bootstrap.sh's redeploy (git clean -fdx). Pass "
              "--state-file to pin it somewhere durable.")
        return fallback


def describe_workspace(author: str, branch: str, main_db: dict | None) -> dict:
    return {
        "author": author,
        "branch": branch,
        "initialized": main_db is not None,
        "db": {k: v for k, v in main_db.items() if k != "password"} if main_db else None,
    }


def db_from_payload(payload: dict, fallback: dict | None) -> dict:
    """Fills in whatever a request's optional {"db": {...}} left out - from `fallback` (main's already-
    stored connection, on a redundant re-init) or DEFAULT_DB (nothing stored yet either)."""
    base = fallback or DEFAULT_DB
    given = payload.get("db") or {}
    return {
        "host": given.get("host") or base["host"],
        "port": int(given.get("port") or base["port"]),
        "database": given.get("database") or base["database"],
        "user": given.get("user") or base["user"],
        "password": given["password"] if given.get("password") not in (None, "") else base["password"],
    }


class WorkspaceStore:
    """{"main_db": {...} | None, "authors": {author: {"branch": ...}}}.

    No lock here. relay.py's HTTP server is threaded, so calls can genuinely overlap, but this store
    leans on lower layers instead of a Python-level threading.Lock: dict get/set are single bytecode
    ops already serialized by the GIL, and _save() writes to a filename unique per call before
    os.replace()-ing it into place, so the OS's own atomic rename - not anything in this class - is
    what stops two concurrent writers from corrupting the file. What this does NOT prevent is two
    overlapping requests read-modify-writing from the same starting snapshot and one clobbering the
    other's update (e.g. a stale branch value briefly winning a race); for a relay whose real state of
    record is the daemon's own advisory-locked metadata store, and where this cache just self-heals on
    the next read, that's an acceptable trade for not owning a second locking scheme.

    Persisted as JSON so it survives a relay restart - relay.py's own restart_daemon() means that
    happens on every deploy. main_db, INCLUDING the plaintext password, is written to --state-file: see
    relay.py's module docstring for why that file must live outside --repo-dir (bootstrap.sh's redeploy
    runs `git clean -fdx`, which would otherwise delete it) and should be kept off any box the relay's
    own unauthenticated /admin/run isn't already trusted on.
    """

    def __init__(self, path: Path):
        self._path = path
        self._data: dict = {"main_db": None, "authors": {}}
        if self._path.is_file():
            try:
                loaded = json.loads(self._path.read_text())
                self._data["main_db"] = loaded.get("main_db")
                self._data["authors"] = loaded.get("authors") or {}
            except (OSError, json.JSONDecodeError):
                pass

    def _save(self):
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(dir=self._path.parent, prefix=self._path.name + ".")
        try:
            with os.fdopen(fd, "w") as tmp_file:
                tmp_file.write(json.dumps(self._data, indent=2))
            os.chmod(tmp_name, 0o600)
            os.replace(tmp_name, self._path)
        except BaseException:
            os.unlink(tmp_name)
            raise

    def main_db(self) -> dict | None:
        db = self._data["main_db"]
        return dict(db) if db else None

    def set_main_db(self, db: dict):
        self._data["main_db"] = db
        self._save()

    def branch_for(self, author: str) -> str:
        record = self._data["authors"].get(author)
        if record is None:
            record = {"branch": "main"}
            self._data["authors"][author] = record
            self._save()
        return record["branch"]

    def set_branch(self, author: str, branch: str):
        record = self._data["authors"].setdefault(author, {"branch": "main"})
        record["branch"] = branch
        self._save()

    def clear(self):
        """Back to a first run: main_db forgotten, every author's branch reset to "main". Call this
        after clear-everything.sh, which TRUNCATEs tracked_databases and resets branch_metadata to
        just 'main' on the daemon side *every* run, --tracked or not - this cache would otherwise go on
        claiming main is still tracked (with credentials that may no longer even be valid) and that
        authors are still on branches the daemon no longer has."""
        self._data = {"main_db": None, "authors": {}}
        self._save()
