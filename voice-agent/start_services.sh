#!/usr/bin/env bash
# Starts the full local voice-agent stack:
#   livekit-server (dev mode, node-ip set for LAN media)  :7880
#   llama-server   (local LLM, OpenAI-compatible)          :8000
#   web_server.py  (token API + static web UI)             :8082
#   caddy          (self-signed HTTPS/WSS reverse proxy)   :8443 (UI + LiveKit signaling, same origin)
#   agent.py dev   (the LiveKit agent worker)
# faster-whisper (STT) and Piper (TTS) run in-process / as subprocesses, no server needed.
set -e
cd "$(dirname "$0")"
LAN_IP="192.168.0.200"

if ! curl -s -o /dev/null http://localhost:7880; then
  echo "starting livekit-server..."
  setsid nohup livekit-server --dev --bind 0.0.0.0 --node-ip "$LAN_IP" > livekit-server.log 2>&1 < /dev/null &
  disown
fi

if ! curl -s -o /dev/null http://localhost:8000/v1/models; then
  echo "starting llama-server..."
  setsid nohup /usr/bin/llama-server \
    -m "$(dirname "$0")/models/qwen2.5-0.5b-instruct-q4_k_m.gguf" \
    --port 8000 --host 0.0.0.0 -c 2048 \
    > llama-server.log 2>&1 < /dev/null &
  disown
fi

if ! curl -s -o /dev/null http://localhost:8082/; then
  echo "starting web_server.py (token API + UI)..."
  setsid nohup venv/bin/python3 web_server.py > web_server.log 2>&1 < /dev/null &
  disown
fi

if ! pgrep -f 'caddy run' > /dev/null; then
  echo "starting caddy (HTTPS/WSS reverse proxy)..."
  setsid nohup caddy run --config Caddyfile --adapter caddyfile > caddy.log 2>&1 < /dev/null &
  disown
fi

if ! pgrep -f 'agent.py dev' > /dev/null; then
  echo "starting agent.py worker..."
  setsid nohup venv/bin/python3 agent.py dev > agent_dev.log 2>&1 < /dev/null &
  disown
fi

sleep 3
echo "livekit-server: $(curl -s http://localhost:7880 || echo DOWN)"
echo "llama-server:   $(curl -s -o /dev/null -w '%{http_code}' http://localhost:8000/v1/models || echo DOWN)"
echo "web_server:     $(curl -s -o /dev/null -w '%{http_code}' http://localhost:8082/ || echo DOWN)"
echo
echo "Open in a browser on the LAN (self-signed cert -> click through the warning,"
echo "then allow microphone access):"
echo "  https://$LAN_IP:8443"
