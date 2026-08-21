#!/usr/bin/env bash
# Container entrypoint: starts the full voice-agent stack (livekit-server,
# llama-server, web_server.py, caddy, agent.py) as one supervised process
# group. If any of them dies, the whole container exits so the orchestrator
# (docker-compose `restart: unless-stopped`) can bring it back up clean.
set -euo pipefail
cd /opt/voice-agent

: "${NODE_IP:?Set NODE_IP in .env to this VPS public IP, used for WebRTC ICE candidates}"
: "${AGENT_MODE:=dev}"
: "${LLM_MODEL_PATH:=models/qwen2.5-0.5b-instruct-q4_k_m.gguf}"
: "${LIVEKIT_API_KEY:?Set LIVEKIT_API_KEY in .env (generate with: docker run --rm voice-agent:latest livekit-server generate-keys). Do not leave this as the public devkey default.}"
: "${LIVEKIT_API_SECRET:?Set LIVEKIT_API_SECRET in .env to match LIVEKIT_API_KEY}"
: "${UDP_PORT:=7882}"

pids=()
cleanup() {
  trap - TERM INT
  echo "entrypoint: shutting down..."
  kill "${pids[@]}" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup TERM INT

echo "entrypoint: starting livekit-server (node-ip=$NODE_IP, udp-port=$UDP_PORT)..."
# --keys overrides --dev's public "devkey: secret" placeholder credentials --
# without this, anyone who reaches this port (it must stay bound to 0.0.0.0
# for WebRTC media, so a firewall gap here isn't hypothetical) could mint
# their own valid tokens with a credential pair published in LiveKit's own
# docs, bypassing Caddy's basic_auth entirely.
# --udp-port pins WebRTC media to one fixed UDP port instead of the default
# wide ephemeral range, so a cloud firewall only needs one UDP rule.
livekit-server --dev --bind 0.0.0.0 --node-ip "$NODE_IP" --udp-port "$UDP_PORT" --keys "$LIVEKIT_API_KEY: $LIVEKIT_API_SECRET" &
pids+=("$!")

echo "entrypoint: starting llama-server..."
# Loopback-only: only agent.py (same container) needs this; it doesn't need
# to be reachable from outside, and llama-server has no auth of its own.
llama-server -m "$LLM_MODEL_PATH" --port 8000 --host 127.0.0.1 -c 2048 &
pids+=("$!")

echo "entrypoint: starting web_server.py..."
venv/bin/python3 web_server.py &
pids+=("$!")

echo "entrypoint: starting caddy..."
caddy run --config Caddyfile --adapter caddyfile &
pids+=("$!")

echo "entrypoint: starting agent.py ($AGENT_MODE)..."
venv/bin/python3 agent.py "$AGENT_MODE" &
pids+=("$!")

wait -n
echo "entrypoint: a service exited, tearing down the rest..."
cleanup
exit 1
