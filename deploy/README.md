# CI/CD: test → canary → auto-promote/rollback

Every push to `main` runs `.github/workflows/ci-cd.yml`:

1. **Test** — `npm ci`, `npm run test:syntax`, `npm test` (66 tests). Anything
   failing here stops the pipeline; nothing touches production.
2. **Pre-deploy snapshot** — `.github/workflows/backup.yml` pulls a verified
   snapshot of production and pushes it to the private DR repo. This runs
   *before* the canary, because the value of the snapshot is being the restore
   point from moments before new code touches the database; one taken
   afterwards has already recorded whatever a bad migration did. The deploy
   job gates on it, so **an unverifiable backup stops the release** —
   production is never changed without a proven way back. See "Backup" below.
3. **Deploy** — GitHub Actions SSHes into the VPS with a key that can do
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

## Backup

`deploy/backup.sh` runs on the VPS and streams a snapshot to stdout: the SQLite
database, uploads, `.env`, the live `Caddyfile`, the pinned `.deploy-state`, and
a `SNAPSHOT_INFO` manifest. The CI job pipes that into a file, proves it
restores (`PRAGMA integrity_check` plus a table-count floor and a check that
`.env` still carries `JWT_SECRET`), and only then pushes it to the private
`rioned/movo-platform-dr` repo.

It runs in two situations:

- **before every deploy**, called from `ci-cd.yml` with `reason=predeploy-<sha>`
- **nightly at 03:00 UTC**, on its own schedule, so the snapshot does not go
  stale between releases

`workflow_dispatch` also runs it on demand.

The database, its `-wal` and its `-shm` are captured in a single `tar`
invocation. This matters more than it looks: the WAL on this host is routinely
*larger* than `movo.db` itself, so a snapshot of the database file alone
restores cleanly, passes its integrity check, and is quietly missing the most
recent writes.

Snapshots overwrite `backups/latest/` in the DR repo rather than accumulating
timestamped directories. They are ~1.8MB of incompressible gzip and git keeps
every version forever, so nightly stamped copies would add roughly 650MB/year.
Git history still holds each version for point-in-time recovery.

## Access model

The GitHub Actions secret `VPS_DEPLOY_KEY` is a dedicated ed25519 key whose
*only* capabilities are running `deploy.sh` for a specific commit and
`backup.sh` for a snapshot — enforced by a `command=` restriction in the VPS's
`authorized_keys` pointing at `deploy/ssh-wrapper.sh`, which accepts nothing but
a bare 40-character git SHA or `backup [reason]` (reason constrained to
`[A-Za-z0-9._-]{1,64}`) before executing anything.

The `backup` verb deliberately reuses this key rather than adding a second
authorized entry point. It grants no meaningful privilege the key lacked: it
could already build and run any commit from the repository on production, which
is strictly more powerful than reading the database. It cannot open a shell,
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
   echo 'command="/opt/movo-platform/deploy/ssh-wrapper.sh",no-port-forwarding,no-X11-forwarding,no-agent-forwarding,no-pty ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8h0E+o1ejRCpw/krF6XFWS2OiZ9R4F7DHqMvqE6GqW github-actions-movo-deploy' >> ~/.ssh/authorized_keys
   ```
2. **Add two GitHub Actions secrets** (repo Settings → Secrets and
   variables → Actions):
   - `VPS_DEPLOY_KEY` — the private half of that same keypair (given to you
     separately, out of band — not committed anywhere in this repo)
   - `VPS_HOST` — `31.97.111.156`

3. **Install the backup scripts on the VPS.** `deploy.sh` clones each release
   into `releases/<sha>/` and never rewrites `/opt/movo-platform/deploy/`
   itself, and that directory is not a git checkout — so changes to these two
   files do **not** reach production on their own. Copy them once, from your
   laptop:
   ```
   scp deploy/backup.sh deploy/ssh-wrapper.sh vps:/opt/movo-platform/deploy/
   ssh vps 'chmod +x /opt/movo-platform/deploy/{backup.sh,ssh-wrapper.sh}'
   ```
   `authorized_keys` already points at `ssh-wrapper.sh`, so no key change is
   needed — the new `backup` verb is live as soon as the file lands.

   Verify before merging:
   ```
   ssh vps 'SSH_ORIGINAL_COMMAND=backup /opt/movo-platform/deploy/ssh-wrapper.sh' \
     > /tmp/snap.tar.gz && tar tzf /tmp/snap.tar.gz
   ```

4. **Give CI write access to the DR repo.** Generate a dedicated key, register
   the public half on `movo-platform-dr` only, and hand the private half to
   this repo:
   ```
   ssh-keygen -t ed25519 -N '' -C movo-dr-backup-push -f ~/.ssh/id_ed25519_movo_dr
   gh repo deploy-key add ~/.ssh/id_ed25519_movo_dr.pub \
     -R rioned/movo-platform-dr --title "ci backup push" --allow-write
   gh secret set DR_REPO_KEY -R rioned/movo-platform \
     < ~/.ssh/id_ed25519_movo_dr
   ```
   A deploy key rather than a personal access token: it reaches exactly one
   repository, so a leak from this **public** repo's CI cannot touch anything
   else you own.

Once all four are done, every push to `main` that passes tests will snapshot
production, verify the snapshot restores, and only then roll out.

## Ordering note

Steps 3 and 4 must be complete **before** this change merges to `main`. The
deploy job now gates on the backup job, so merging first would leave the
pipeline unable to ship until the VPS scripts and `DR_REPO_KEY` are in place.
That gate is the intended behaviour, not a side effect — but it is worth
choosing when to switch it on.

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
