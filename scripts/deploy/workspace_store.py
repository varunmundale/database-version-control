#!/usr/bin/env python3
"""State for the dbgit web relay (see relay.py's module docstring for the wire protocol this feeds).

Two things are tracked, with two different shapes: `main`'s tracked connection is a singleton shared
by every author (there is no such thing as "an author's own main"), while which branch an author has
checked out is per-author - the same thing `.dbgit/HEAD` is per local directory for the real CLI,
keyed by author instead of by filesystem path since a browser has none of its own.
"""
import json
import os
import tempfile
from pathlib import Path

# What `dbgit init` runs against when nothing says otherwise, matching setup.sh's "production"
# Postgres on :5433.
DEFAULT_DB = {"host": "localhost", "port": 5433, "database": "postgres", "user": "postgres", "password": "postgres"}


def default_state_path(repo_dir: Path) -> Path:
    """/etc/dbgit outlives a bootstrap.sh redeploy's `git clean -fdx`, unlike anything under repo_dir.
    Falls back to inside the repo only if /etc isn't writable, and says so."""
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

    No lock here: dict get/set are already GIL-serialized, and _save() writes to a unique temp file
    before an atomic os.replace() into place, so concurrent writers can't corrupt the file. What this
    does not prevent is two overlapping requests clobbering each other's update - an acceptable trade
    since the daemon's own advisory-locked metadata store is the real state of record and this cache
    just self-heals on the next read.

    Persisted as JSON (main_db's plaintext password included) so it survives a relay restart, which
    happens on every deploy.
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
        after clear-everything.sh, which resets the same state on the daemon side."""
        self._data = {"main_db": None, "authors": {}}
        self._save()
