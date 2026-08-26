# Deployment

Two ways to run MOVO in production: a single PM2-managed process (simple,
manual), or the automated canary pipeline that actually runs on the project's
VPS today. Both assume the environment variables in
[`ENVIRONMENT.md`](ENVIRONMENT.md) are set correctly — check `GET /ready`
after any deploy, not just `GET /health`.

## Why one process

MOVO is a modular monolith over an in-process SQLite database
(`better-sqlite3`) — see [`ARCHITECTURE.md`](ARCHITECTURE.md#why-a-monolith).
That means the deployment model is "keep one process healthy and reachable,"
not "manage a fleet." `ecosystem.config.js` deliberately configures a single
PM2 instance (`instances: 1, exec_mode: 'fork'`) — adding PM2 cluster workers
would give you multiple processes contending for the same SQLite file, not
more capacity.

## Option 1: PM2 (simple, manual)

```bash
npm ci
cp .env.example .env   # fill in real values — see ENVIRONMENT.md
npm run start:pm2      # pm2 start ecosystem.config.js --update-env
```

`ecosystem.config.js` sets production defaults (`NODE_ENV=production`,
`OTP_TEST_MODE=false`, `TRUST_PROXY=true`, `HTTPS_ONLY=true`,
`RATE_LIMIT_ENABLED=true`) and:

- `max_memory_restart: '400M'` — restarts the process if it leaks past this.
- `kill_timeout: 12000` — gives the graceful-shutdown path
  (`SHUTDOWN_GRACE_MS`, default `10000`) room to drain connections, close
  Socket.IO, and checkpoint the SQLite WAL before PM2 sends `SIGKILL`.
- `autorestart: true` — restarts on crash.

You still need a reverse proxy (Caddy, nginx) in front of this for TLS
termination; `HTTPS_ONLY`/`TRUST_PROXY` assume one hop of a proxy you
control, not the internet directly.

**Rolling out a new version this way is manual and has no automatic
rollback** — stop the old process, pull the new code, `npm ci`, restart, and
watch `GET /health`/`GET /ready` yourself. For anything with real traffic,
use Option 2 instead.

## Option 2: automated canary (what's actually running in production)

Every push to `main` runs [`.github/workflows/ci-cd.yml`](.github/workflows/ci-cd.yml):

1. **Test** — `npm ci`, `npm run test:syntax`, `npm test`. A failure here
   stops the pipeline before anything touches production.
2. **Deploy** — GitHub Actions SSHes into the VPS with a key that can do
   exactly one thing (see "Access model" below): trigger
   [`deploy/deploy.sh <sha>`](deploy/deploy.sh). Everything from here on
   happens **on the VPS**, not in the CI runner, so the rollout logic
   survives even if the runner disappears mid-deploy:
   - builds the commit as a `movo-platform:<sha>` Docker image
   - starts it as `movo-canary` alongside the running `movo` (stable),
     sharing the same SQLite file — safe because of WAL mode + busy_timeout,
     already configured in `server.js`
   - waits for the canary's Docker healthcheck to pass
   - shifts **20%** of live traffic to it via Caddy's weighted load balancer
   - bakes for 5 minutes (`CANARY_BAKE_SECONDS`, default 300), polling every
     15s for: healthcheck status, container restarts (crash-loop), and new
     `"level":"error"` structured log lines
   - **any of those trip → automatic rollback**: 100% traffic back to old
     stable, canary torn down, workflow exits non-zero (shows red in GitHub)
   - **clean bake → automatic promotion**: 100% traffic to the canary, the
     old `movo` container is replaced with the same image (so the next
     deploy's "stable" slot is correct), health-checked again, then Caddy
     points back at a single `movo` upstream

No human is in the loop for either outcome. Full detail, the access model
(a forced-command-restricted SSH key that can only run `deploy.sh` for a
specific commit SHA), one-time VPS setup, manual rollback instructions, and
the known SQLite-sharing limitation are documented in
[`deploy/README.md`](deploy/README.md) — read that before touching the
pipeline itself.

Android app changes are built and unit-tested by the same CI workflow's test
job but are **not** auto-shipped to a store — they still require manual
signing and a manual release (Play Store / internal distribution).

### Tuning the canary

Set at the top of `deploy/deploy.sh`:

| Variable | Default | Meaning |
|---|---|---|
| `CANARY_BAKE_SECONDS` | `300` | How long to run at partial traffic before auto-promoting |
| `CANARY_WEIGHT` | `20` | Percent of traffic shifted to the canary during the bake |
| `CANARY_MAX_ERRORS` | `5` | New error-level log lines during the bake that trigger rollback |

### Manual rollback

If something slips through anyway:

```bash
ssh vps
cd /opt/movo-platform && docker compose logs movo --tail 100   # see what's running
bash deploy/deploy.sh <previous-good-sha>                        # redeploy through the same canary process
```

## Pre-deployment checklist

Before pointing either deployment path at real traffic, confirm (see
`README.md`'s "Security and pilot readiness" section for the full list):

1. `JWT_SECRET` is set through a secret manager, not generated per-boot.
2. `BCRYPT_ROUNDS=12` in production.
3. A contracted SMS provider is connected and `OTP_TEST_MODE=false`
   (the server refuses to start otherwise — see [`ENVIRONMENT.md`](ENVIRONMENT.md)).
4. TLS terminates in front of the app; `TRUST_PROXY`/`HTTPS_ONLY` are set
   correctly for your proxy topology.
5. Both Android manifests have `usesCleartextTraffic` removed and
   `API_BASE_URL` pointed at an HTTPS host before a public release build.
6. `GET /ready` reports `ready: true` with no failures.

## Database backup

There is no automated backup job in this repository yet — `movo.db` (plus
its `-wal`/`-shm` files) is the entire state of the platform. At minimum,
snapshot it on a schedule with SQLite's own backup mechanism
(`VACUUM INTO` or the `.backup` CLI command) rather than copying the raw
file while the server is running, and verify you can actually restore from a
snapshot before you need to. This is called out as an open item in
`README.md`'s pilot-readiness checklist — treat it as a blocker for a real
production launch, not a nice-to-have.
