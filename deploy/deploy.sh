#!/usr/bin/env bash
# Canary deploy for movo-platform: builds a commit as a new image, runs it
# alongside the current stable container at a 20% traffic split via Caddy's
# weighted load balancer, bakes for a monitoring window, then either
# promotes it to 100% (new stable) or rolls back to the old stable — fully
# automatically, no human in the loop.
#
# Usage: deploy.sh <git-sha>
# Must be run on the VPS as root, from /opt/movo-platform.

set -euo pipefail

REPO_URL="https://github.com/rioned/movo-platform.git"
ROOT=/opt/movo-platform
RELEASES="$ROOT/releases"
COMPOSE="docker compose -f $ROOT/docker-compose.yml"
CADDYFILE="$ROOT/Caddyfile"
STATE_FILE="$ROOT/.deploy-state"

CANARY_BAKE_SECONDS="${CANARY_BAKE_SECONDS:-300}"   # 5 min at 20% traffic
CANARY_POLL_INTERVAL="${CANARY_POLL_INTERVAL:-15}"
CANARY_MAX_ERRORS="${CANARY_MAX_ERRORS:-5}"          # error-level log lines tolerated during bake
CANARY_WEIGHT="${CANARY_WEIGHT:-20}"                 # % of traffic sent to canary during bake

SHA="${1:?usage: deploy.sh <git-sha>}"
if ! [[ "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "ERROR: SHA must be a full 40-character git commit hash, got: $SHA" >&2
  exit 1
fi

log() { echo "[deploy $(date -u +%H:%M:%S)] $*"; }

set_caddy_weights() {
  local canary_w=$1 stable_w=$2
  if [[ "$canary_w" -eq 0 ]]; then
    # Single upstream — avoids a 0-weight upstream still being dialed for health checks.
    python3 - "$CADDYFILE" <<'PYEOF'
import re, sys
path = sys.argv[1]
text = open(path).read()
text = re.sub(
    r"reverse_proxy \{[^}]*\}",
    "reverse_proxy movo:3000",
    text, count=1, flags=re.S
)
open(path, "w").write(text)
PYEOF
  else
    python3 - "$CADDYFILE" "$canary_w" "$stable_w" <<'PYEOF'
import re, sys
path, canary_w, stable_w = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(path).read()
block = (
    "reverse_proxy {\n"
    "\t\tto movo:3000 movo-canary:3000\n"
    f"\t\tlb_policy weighted {stable_w} {canary_w}\n"
    "\t}"
)
if "reverse_proxy movo:3000" in text:
    text = text.replace("reverse_proxy movo:3000", block, 1)
else:
    text = re.sub(r"reverse_proxy \{[^}]*\}", block, text, count=1, flags=re.S)
open(path, "w").write(text)
PYEOF
  fi
  docker exec movo-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
}

rollback() {
  log "ROLLING BACK — restoring 100% traffic to stable, tearing down canary"
  set_caddy_weights 0 100
  $COMPOSE --profile canary rm -sf movo-canary 2>/dev/null || true
  log "Rollback complete. Stable remains on previous release."
  exit 1
}

log "=== Deploying $SHA (canary rollout) ==="

# 1. Fetch and build the target commit in an isolated release dir.
RELEASE_DIR="$RELEASES/$SHA"
if [[ ! -d "$RELEASE_DIR" ]]; then
  log "Cloning $SHA into $RELEASE_DIR"
  git clone --quiet "$REPO_URL" "$RELEASE_DIR"
  git -C "$RELEASE_DIR" checkout --quiet "$SHA"
fi

log "Building image movo-platform:$SHA"
docker build -q -t "movo-platform:$SHA" "$RELEASE_DIR" >/dev/null

# 2. Start canary alongside stable, on the same network/volumes/.env.
log "Starting canary container"
CANARY_SHA="$SHA" $COMPOSE --profile canary up -d movo-canary

log "Waiting for canary healthcheck..."
for i in $(seq 1 20); do
  status=$(docker inspect -f '{{.State.Health.Status}}' movo-platform-canary 2>/dev/null || echo "missing")
  [[ "$status" == "healthy" ]] && break
  [[ "$status" == "unhealthy" ]] && { log "Canary failed healthcheck immediately"; rollback; }
  sleep 3
done
[[ "$status" == "healthy" ]] || { log "Canary never became healthy (status: $status)"; rollback; }
log "Canary is healthy."

# 3. Shift 20% of live traffic to canary.
log "Shifting ${CANARY_WEIGHT}% of traffic to canary"
set_caddy_weights "$CANARY_WEIGHT" $((100 - CANARY_WEIGHT))

# 4. Bake: watch health + restarts + error logs for the monitoring window.
log "Baking for ${CANARY_BAKE_SECONDS}s, watching health/restarts/errors..."
elapsed=0
baseline_errors=$(docker logs movo-platform-canary 2>&1 | grep -c '"level":"error"' || true)
while [[ $elapsed -lt $CANARY_BAKE_SECONDS ]]; do
  sleep "$CANARY_POLL_INTERVAL"
  elapsed=$((elapsed + CANARY_POLL_INTERVAL))

  status=$(docker inspect -f '{{.State.Health.Status}}' movo-platform-canary 2>/dev/null || echo "missing")
  restarts=$(docker inspect -f '{{.RestartCount}}' movo-platform-canary 2>/dev/null || echo "99")
  errors=$(docker logs movo-platform-canary 2>&1 | grep -c '"level":"error"' || true)
  new_errors=$((errors - baseline_errors))

  log "t=${elapsed}s health=$status restarts=$restarts new_errors=$new_errors"

  if [[ "$status" != "healthy" ]]; then
    log "Canary unhealthy during bake"; rollback
  fi
  if [[ "$restarts" -gt 0 ]]; then
    log "Canary restarted during bake (crash loop suspected)"; rollback
  fi
  if [[ "$new_errors" -gt "$CANARY_MAX_ERRORS" ]]; then
    log "Canary exceeded error budget ($new_errors > $CANARY_MAX_ERRORS)"; rollback
  fi
done

# 5. No issues — shift all live traffic to canary, then re-provision the
#    "movo" service from the same image so it becomes the new stable slot
#    (keeps the Caddyfile's upstream names stable across deploys). The old
#    stable container is stopped only after canary is taking 100% of
#    traffic, so this causes zero downtime regardless of how long the
#    "movo" re-provision step takes.
log "Bake window clean. Shifting 100% of traffic to canary."
set_caddy_weights 100 0
sleep 2  # let in-flight stable requests drain before we stop it

$COMPOSE stop movo
$COMPOSE rm -f movo
STABLE_SHA="$SHA" $COMPOSE up -d movo

log "Waiting for re-provisioned stable container to become healthy..."
status="missing"
for i in $(seq 1 20); do
  status=$(docker inspect -f '{{.State.Health.Status}}' movo-platform 2>/dev/null || echo "missing")
  [[ "$status" == "healthy" ]] && break
  sleep 3
done

if [[ "$status" == "healthy" ]]; then
  echo "STABLE_SHA=$SHA" > "$STATE_FILE"
  set_caddy_weights 0 100   # single upstream again, now pointing at the promoted movo
  $COMPOSE --profile canary rm -sf movo-canary 2>/dev/null || true
  ls -1dt "$RELEASES"/*/ 2>/dev/null | tail -n +6 | xargs -r rm -rf
  log "=== Deploy of $SHA complete. Now serving 100% of traffic. ==="
else
  # Traffic is still 100% on the (proven-healthy) canary container, so
  # production is fine either way — this only means the "movo" bookkeeping
  # slot didn't come up cleanly. Leave weights as-is (canary serving
  # everything) and fail loudly instead of silently leaving state
  # inconsistent for the next deploy.
  log "ERROR: re-provisioned stable container did not become healthy (status: $status)."
  log "Traffic remains on the canary container (100%), which is proven healthy — service is NOT down."
  log "Manual follow-up needed: investigate 'docker logs movo-platform' and re-run the deploy."
  exit 1
fi
