# Deploying it as a service

For running `dbgit` somewhere other than a laptop, with a browser UI in front of it instead of the CLI:

```bash
curl -fsSL https://raw.githubusercontent.com/varunmundale/database-version-control/master/scripts/deploy/bootstrap.sh | bash
```

One command, no configuration required. `bootstrap.sh` clones the repo onto the machine; `setup.sh`
then installs Docker, brings up three Postgres containers (the metadata store, the database `main`
tracks, and the branch-fork scratchpad), installs a JDK, builds the project, and installs two systemd
services — `dbgit-daemon` (`dbService`) and `dbgit-relay`, an HTTP-to-TCP bridge
(`scripts/deploy/relay.py`) that lets a browser speak `dbgit`'s raw-TCP protocol, since a browser can't
open a raw socket itself. Visiting `http://<vm>:8080/` opens `scripts/deploy/web/index.html`, a browser
client for the same commands the CLI sends: each visitor gets their own branch/author identity, and
`main`'s tracked connection is shared across all of them, set up once through the UI's own init step.

Re-run `bootstrap.sh` any time to redeploy the latest `master`. `./scripts/deploy/setup.sh clean` tears
down everything it created (containers, services, build output) without touching the Docker/JDK
installs themselves.

This sits in front of the same daemon and wire protocol described in
[`docs/architecture.md`](architecture.md) — the relay doesn't change how `dbgit` works, it just gives
a browser a way to reach it.

← [back to README](../README.md)
