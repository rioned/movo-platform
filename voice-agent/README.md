# I.S.H.E.M.A — Local Voice Agent

A self-hosted, offline-capable LiveKit voice agent: browser-based voice UI, faster-whisper
STT, Piper TTS, a local LLM via llama-server (or any OpenAI-compatible / Hermes Agent CLI
provider), and a simple web-page RAG feature, all fronted by Caddy over HTTPS.

## Not included in this repo

Two things are excluded (see `.gitignore`) because they're large binaries, not source, and
some individually exceed GitHub's 100MB file limit:

- **`models/`** — `qwen2.5-0.5b-instruct-q4_k_m.gguf` (llama-server), faster-whisper
  models under `models/whisper/<name>/`, and Piper voices under `models/piper/`.
- **`piper/`** — the Piper TTS binary + shared libs + espeak-ng data
  (from a [Piper release](https://github.com/OHF-Voice/piper1-gpl/releases) or
  [rhasspy/piper](https://github.com/rhasspy/piper) release for your architecture).

Place them in the same layout under `voice-agent/` before building — `agent.py` and
`web_server.py` look for them there (see `resolve_whisper_model`/`resolve_piper_voice` in
`agent.py`, and `PIPER_BIN`/`WHISPER_MODELS_DIR`/`PIPER_MODELS_DIR`).

## Running it

Everything needed to deploy is a Docker image + `docker-compose.yml`:

```bash
cp .env.example .env
# fill in .env: NODE_IP, PUBLIC_HOST, a real LIVEKIT_API_KEY/SECRET
#   (docker run --rm voice-agent:latest livekit-server generate-keys),
#   and BASIC_AUTH_USER/HASH (docker run --rm voice-agent:latest
#   caddy hash-password --plaintext 'your-password')
docker compose up -d --build
```

See the comments in `.env.example` and `Caddyfile` for what each setting controls —
notably why LiveKit's signaling deliberately lives on its own origin
(`LIVEKIT_SIGNAL_PORT`) separate from the password-protected web UI.

`entrypoint.sh` runs the full stack in one container: `livekit-server`, `llama-server`,
`web_server.py` (the token-minting API + static UI), `caddy`, and `agent.py` (the LiveKit
agent worker).

## Local (non-Docker) development

```bash
python3 -m venv venv && source venv/bin/activate
pip install -r requirements.txt
./start_services.sh
```

`start_services.sh` starts everything for local/LAN use (self-signed HTTPS). `test_*.py`
are ad-hoc pipeline/plugin tests; `pw_top5.py`/`pw_top5.js`/`pw_top10.js` are Playwright
scraping experiments unrelated to the voice agent itself.
