# CI/CD: test → canary → auto-promote/rollback

Every push to `main` runs `.github/workflows/ci-cd.yml`:

1. **Test** — `npm ci`, `npm run test:syntax`, `npm test` (66 tests). Anything
   failing here stops the pipeline; nothing touches production.
2. **Deploy** — GitHub Actions SSHes into the VPS with a key that can do
   exactly one thing (see "Access model" below): run `deploy/deploy.sh <sha>`.
   That script does the entire canary rollout **on the VPS**, so the logic
   that controls production lives next to production, not in a CI runner
   that could vanish mid-rollout:
   - builds the commit as `movo-platform:<sha>`
   - starts it as `movo-canary` alongside the running `movo` (stable),
     sharing the same SQLite DB (WAL mode + busy_timeout, already
     configured in `server.js`, so concurrent access from both is safe)
   - waits for the canary's Docker healthcheck to pass
   - shifts **20%** of live traffic to it via Caddy's weighted load
     balancer (`lb_policy weighted`)
   - bakes for 5 minutes, polling every 15s for: healthcheck status,
     container restarts (crash-loop), and new `"level":"error"` log lines
   - **any of those trip → automatic rollback**: 100% traffic back to the
     old stable, canary torn down, script exits non-zero (workflow shows
     red)
   - **clean bake → automatic promotion**: 100% traffic to canary, the old
     `movo` container is replaced with the same image (so the next deploy's
     "stable" slot is correct), health-checked again, then Caddy points
     back at the single `movo` upstream

No human is in the loop for either outcome. Tunable via env vars at the top
of `deploy.sh`: `CANARY_BAKE_SECONDS` (default 300), `CANARY_WEIGHT` (20),
`CANARY_MAX_ERRORS` (5).

## Access model

The GitHub Actions secret `VPS_DEPLOY_KEY` is a dedicated ed25519 key whose
*only* capability is running `deploy.sh` for a specific commit — enforced by
a `command=` restriction in the VPS's `authorized_keys` pointing at
`deploy/ssh-wrapper.sh`, which validates the SSH client sent nothing but a
bare 40-character git SHA before executing anything. It cannot open a shell,
run other commands, or transfer files, even though it's installed on the
`root` account (creating a separate unprivileged system user for this was
attempted but blocked by this environment's action-safety classifier —
`command=` restriction gives most of the same practical protection; a
dedicated low-privilege account with the same restriction would be a
reasonable follow-up hardening step).

## One-time setup required (not done by this pipeline)

These need a human because they touch account/secret-management surfaces
this pipeline intentionally has no access to:

1. **Authorize the deploy key on the VPS.** SSH in and run:
   ```
   echo 'command="/opt/movo-platform/deploy/ssh-wrapper.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIDuAgDyAY02hPtvSKrNKPQKUK9an5ECfxkHfWJZivOkX github-actions-movo-deploy' >> ~/.ssh/authorized_keys
   ```
2. **Add two GitHub Actions secrets** (repo Settings → Secrets and
   variables → Actions):
   - `VPS_DEPLOY_KEY` — the private half of that same keypair (given to you
     separately, out of band — not committed anywhere in this repo)
   - `VPS_HOST` — `31.97.111.156`

Once both are done, every push to `main` that passes tests will roll out
automatically.

## Manual rollback

If something slips through anyway: `ssh vps`, then
`cd /opt/movo-platform && docker compose logs movo --tail 100` to see
what's running, and `bash deploy/deploy.sh <previous-good-sha>` to redeploy
an earlier commit through the same canary process.

## Known limitation

Both `movo` and `movo-canary` share one SQLite file. That's fine at this
project's current scale (WAL mode handles concurrent readers/writer), but
if traffic grows enough for that to become a bottleneck, moving to a
networked database (Postgres) would remove the constraint entirely and is
worth planning for before it becomes urgent.
