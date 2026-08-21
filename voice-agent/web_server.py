"""Minimal local web server for the voice-agent browser UI.

Serves the static frontend (./web/) and two endpoints:

  POST /token   mints a LiveKit access token for a fresh room, so the
                browser can join and start a realtime voice conversation
                with the agent worker. Also lets the browser pick, per
                session: which LLM to use (local llama-server or any
                OpenAI-compatible API), a custom personality/instructions
                prompt, and a "knowledge" URL to scrape and ground answers
                in (RAG). All of this is stamped into the room's metadata
                (as JSON) before the token is issued, and agent.py reads
                it back once it joins the room.

  POST /scrape  fetches+chunks a URL's text content (via rag.py) and
                caches it to disk, so the settings UI can show "N chunks
                indexed" before the user even connects.

stdlib-only except for `requests` (already used elsewhere) and the
livekit-api client.
"""
from __future__ import annotations

import asyncio
import json
import os
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

from livekit import api

import rag

LIVEKIT_URL_PUBLIC = os.environ.get("LIVEKIT_URL_PUBLIC", "wss://192.168.0.200:8443")
LIVEKIT_URL_LOCAL = os.environ.get("LIVEKIT_URL_LOCAL", "http://localhost:7880")
API_KEY = os.environ.get("LIVEKIT_API_KEY", "devkey")
API_SECRET = os.environ.get("LIVEKIT_API_SECRET", "secret")
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
WEB_DIR = os.path.join(BASE_DIR, "web")
WHISPER_MODELS_DIR = os.path.join(BASE_DIR, "models", "whisper")
PIPER_MODELS_DIR = os.path.join(BASE_DIR, "models", "piper")
PORT = int(os.environ.get("WEB_PORT", "8082"))


def list_whisper_models() -> list[str]:
    """Whisper (faster-whisper/CTranslate2) models available under models/whisper/,
    one subfolder per model (must contain model.bin)."""
    if not os.path.isdir(WHISPER_MODELS_DIR):
        return []
    names = []
    for entry in sorted(os.listdir(WHISPER_MODELS_DIR)):
        d = os.path.join(WHISPER_MODELS_DIR, entry)
        if os.path.isdir(d) and os.path.isfile(os.path.join(d, "model.bin")):
            names.append(entry)
    return names


def list_piper_voices() -> list[str]:
    """Piper TTS voices available under models/piper/, one <name>.onnx (+
    <name>.onnx.json) pair per voice."""
    if not os.path.isdir(PIPER_MODELS_DIR):
        return []
    names = []
    for entry in sorted(os.listdir(PIPER_MODELS_DIR)):
        if entry.endswith(".onnx") and os.path.isfile(
            os.path.join(PIPER_MODELS_DIR, entry + ".json")
        ):
            names.append(entry[: -len(".onnx")])
    return names

ALLOWED_PROVIDERS = {"local", "api", "hermes"}
PROVIDERS_REQUIRING_API_KEY = {"api"}
MAX_INSTRUCTIONS_LEN = 4000


def make_token(identity: str, room: str) -> str:
    grants = api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True)
    return (
        api.AccessToken(API_KEY, API_SECRET)
        .with_identity(identity)
        .with_name(identity)
        .with_grants(grants)
        .to_jwt()
    )


async def create_room_with_metadata(room: str, metadata: dict) -> None:
    lk = api.LiveKitAPI(url=LIVEKIT_URL_LOCAL, api_key=API_KEY, api_secret=API_SECRET)
    try:
        await lk.room.create_room(
            api.CreateRoomRequest(name=room, metadata=json.dumps(metadata))
        )
    finally:
        await lk.aclose()


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, payload: dict, status: int = 200) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _handle_scrape(self, params: dict) -> None:
        url = (params.get("url") or "").strip()
        force = bool(params.get("force"))
        if not url.startswith(("http://", "https://")):
            self._send_json({"error": "url must start with http:// or https://"}, status=400)
            return
        try:
            data = rag.scrape_url(url, force=force)
        except Exception as e:  # noqa: BLE001
            self._send_json({"error": f"failed to fetch/parse url: {e}"}, status=502)
            return
        chunks = data["chunks"]
        self._send_json(
            {
                "kb_id": data["kb_id"],
                "url": data["url"],
                "num_chunks": len(chunks),
                "preview": chunks[0][:220] if chunks else "",
            }
        )

    def _handle_token(self, params: dict) -> None:
        name = params.get("name") or f"user-{uuid.uuid4().hex[:6]}"
        room = params.get("room") or f"voice-agent-{int(time.time())}"
        provider = (params.get("provider") or "local").strip().lower()
        api_key = (params.get("api_key") or "").strip()
        model = (params.get("model") or "").strip()
        base_url = (params.get("base_url") or "").strip()
        instructions = (params.get("instructions") or "").strip()[:MAX_INSTRUCTIONS_LEN]
        knowledge_url = (params.get("knowledge_url") or "").strip()
        whisper_model = (params.get("whisper_model") or "").strip()
        tts_voice = (params.get("tts_voice") or "").strip()

        if whisper_model and whisper_model not in list_whisper_models():
            self._send_json({"error": f"unknown whisper model {whisper_model!r}"}, status=400)
            return
        if tts_voice and tts_voice not in list_piper_voices():
            self._send_json({"error": f"unknown piper voice {tts_voice!r}"}, status=400)
            return

        if provider not in ALLOWED_PROVIDERS:
            self._send_json({"error": f"unknown provider {provider!r}"}, status=400)
            return
        if provider in PROVIDERS_REQUIRING_API_KEY and not api_key:
            self._send_json(
                {"error": f"provider {provider!r} requires an api_key"}, status=400
            )
            return

        # Always stamp the UI's choice into room metadata explicitly -- including
        # "local" -- so the picker is authoritative and never silently falls
        # through to whatever the agent's own .env default happens to be.
        metadata: dict = {"llm_provider": provider}
        if provider == "api":
            # model/base_url/api_key are meaningless for "local" (fixed
            # llama-server) and "hermes" (its own config); only forward them
            # for "api" so a stale value from a previously-selected provider
            # can't leak into an unrelated one.
            if api_key:
                metadata["llm_api_key"] = api_key
            if model:
                metadata["llm_model"] = model
            if base_url:
                metadata["llm_base_url"] = base_url
        if instructions:
            metadata["agent_instructions"] = instructions
        if whisper_model:
            metadata["whisper_model"] = whisper_model
        if tts_voice:
            metadata["tts_voice"] = tts_voice

        if knowledge_url:
            try:
                kb = rag.scrape_url(knowledge_url)  # cached if already scraped
            except Exception as e:  # noqa: BLE001
                self._send_json(
                    {"error": f"failed to prepare knowledge base: {e}"}, status=502
                )
                return
            metadata["kb_id"] = kb["kb_id"]
            metadata["kb_url"] = kb["url"]

        try:
            asyncio.run(create_room_with_metadata(room, metadata))
        except Exception as e:  # noqa: BLE001
            self._send_json({"error": f"failed to configure room: {e}"}, status=502)
            return

        token = make_token(name, room)
        self._send_json(
            {
                "url": LIVEKIT_URL_PUBLIC,
                "token": token,
                "identity": name,
                "room": room,
                "provider": provider,
                "kb_id": metadata.get("kb_id"),
            }
        )

    def do_POST(self) -> None:  # noqa: N802
        path = urlparse(self.path).path
        if path not in ("/token", "/scrape"):
            self.send_error(404, "not found")
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        raw = self.rfile.read(length) if length else b"{}"
        try:
            params = json.loads(raw or b"{}")
        except json.JSONDecodeError:
            self._send_json({"error": "invalid JSON body"}, status=400)
            return
        if path == "/scrape":
            self._handle_scrape(params)
        else:
            self._handle_token(params)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)

        if parsed.path == "/token":
            # convenience GET path for the simple/local case (no api key in a URL)
            self._handle_token({})
            return

        if parsed.path == "/models":
            self._send_json(
                {"whisper": list_whisper_models(), "piper": list_piper_voices()}
            )
            return

        # static file serving
        path = parsed.path
        if path == "/":
            path = "/index.html"
        fs_path = os.path.normpath(os.path.join(WEB_DIR, path.lstrip("/")))
        # startswith(WEB_DIR) alone would also match a sibling dir like
        # "web-secrets" (its path starts with the same characters); requiring
        # the OS separator right after WEB_DIR closes that traversal escape.
        if not (fs_path == WEB_DIR or fs_path.startswith(WEB_DIR + os.sep)) or not os.path.isfile(fs_path):
            self.send_error(404, "not found")
            return

        ctype = "text/html"
        if fs_path.endswith(".js"):
            ctype = "application/javascript"
        elif fs_path.endswith(".css"):
            ctype = "text/css"

        with open(fs_path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args) -> None:  # quieter logs
        print(f"[web] {self.address_string()} {fmt % args}")


if __name__ == "__main__":
    # Loopback-only: Caddy (same host) reverse-proxies this and enforces
    # basic_auth. Binding 0.0.0.0 here would let anyone reach the token/RAG
    # API directly on :8082, bypassing that password entirely.
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"serving on :{PORT}, livekit public url = {LIVEKIT_URL_PUBLIC}")
    server.serve_forever()
