from __future__ import annotations

import json
import logging
import os

from dotenv import load_dotenv
from livekit import agents
from livekit.agents import Agent, AgentSession, JobContext, WorkerOptions, cli, llm as llm_types
from livekit.plugins import openai, silero

from custom_stt import FasterWhisperSTT
from custom_tts import PiperTTS
import hermes_llm
import rag

load_dotenv(os.path.join(os.path.dirname(__file__), ".env"))

logger = logging.getLogger("local-voice-agent")

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PIPER_BIN = os.path.join(BASE_DIR, "piper", "piper", "piper")
WHISPER_MODELS_DIR = os.path.join(BASE_DIR, "models", "whisper")
PIPER_MODELS_DIR = os.path.join(BASE_DIR, "models", "piper")

# Defaults; both are per-session overridable from the web UI's model pickers
# (see resolve_session_config / list_whisper_models / list_piper_voices in
# web_server.py, which is the source of truth for what's actually on disk).
DEFAULT_WHISPER_MODEL = os.environ.get("WHISPER_MODEL", "small.en")
DEFAULT_TTS_VOICE = os.environ.get("TTS_VOICE", "en_US-lessac-medium")


def resolve_whisper_model(name: str) -> str:
    """A local models/whisper/<name>/ folder if present, else the bare name
    (faster-whisper will download it from Hugging Face by name)."""
    local_dir = os.path.join(WHISPER_MODELS_DIR, name)
    if os.path.isfile(os.path.join(local_dir, "model.bin")):
        return local_dir
    return name


def resolve_piper_voice(name: str) -> str:
    """Path to models/piper/<name>.onnx, falling back to the default voice
    if the requested one isn't actually on disk."""
    path = os.path.join(PIPER_MODELS_DIR, f"{name}.onnx")
    if os.path.isfile(path) and os.path.isfile(path + ".json"):
        return path
    default_path = os.path.join(PIPER_MODELS_DIR, f"{DEFAULT_TTS_VOICE}.onnx")
    if name != DEFAULT_TTS_VOICE:
        logger.warning("piper voice %r not found on disk, falling back to %r", name, DEFAULT_TTS_VOICE)
    return default_path

DEFAULT_INSTRUCTIONS = (
    "You are a helpful, friendly voice assistant. Keep responses "
    "concise, ideally under 2 sentences, since they will be spoken aloud."
)

# Local llama-server (default, fully offline)
LOCAL_LLM_MODEL = os.environ.get("LLM_MODEL", "qwen2.5-0.5b-instruct")
LOCAL_LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "http://localhost:8000/v1")

# Server-wide default for the "api" provider: any OpenAI-compatible endpoint
# (DeepSeek, OpenAI itself, Groq, Together, etc. -- anything speaking the
# /v1/chat/completions wire format). Settable in .env, and overridable per
# session via room metadata, which is how the web UI's picker takes effect.
DEFAULT_LLM_PROVIDER = os.environ.get("LLM_PROVIDER", "local")  # "local" | "api" | "hermes"
DEFAULT_API_BASE_URL = os.environ.get("API_BASE_URL", "https://api.deepseek.com/v1")
DEFAULT_API_KEY = os.environ.get("API_KEY", "")
DEFAULT_API_MODEL = os.environ.get("API_MODEL", "deepseek-chat")

# Hermes Agent CLI (Nous Research's autonomous agent, already installed on
# this host) as a third, opt-in provider: replaces the direct LLM call with
# a full agentic loop (tools, skills, memory) invoked via subprocess.
HERMES_BIN = os.environ.get("HERMES_BIN", os.path.expanduser("~/.local/bin/hermes"))
HERMES_TIMEOUT = float(os.environ.get("HERMES_TIMEOUT", "90"))
# Isolated scratch dir Hermes is confined to -- never this project's folder,
# so its autonomous tool-use can't touch our files (see hermes_llm.py).
HERMES_WORKDIR = os.environ.get("HERMES_WORKDIR", hermes_llm.DEFAULT_HERMES_WORKDIR)

RAG_TOP_K = 3


def build_llm(
    provider: str,
    api_key: str | None = None,
    model: str | None = None,
    base_url: str | None = None,
):
    """Construct the LLM plugin for the given provider.

    provider="local"   -> local llama-server (fully offline, no API key needed)
    provider="api"      -> any OpenAI-compatible endpoint (DeepSeek by default,
                          but base_url/model can point anywhere that speaks
                          the OpenAI chat-completions wire format)
    provider="hermes"   -> the Hermes Agent CLI installed on this machine, run
                          as a subprocess per turn instead of a direct LLM call
    """
    if provider == "hermes":
        return hermes_llm.HermesLLM(
            hermes_bin=HERMES_BIN, timeout=HERMES_TIMEOUT, workdir=HERMES_WORKDIR
        )

    if provider == "api":
        key = api_key or DEFAULT_API_KEY
        if not key:
            raise ValueError(
                "API provider selected but no API key was given "
                "(set API_KEY in .env, or pass one from the web UI)"
            )
        return openai.LLM(
            model=model or DEFAULT_API_MODEL,
            api_key=key,
            base_url=base_url or DEFAULT_API_BASE_URL,
        )

    # default: local
    return openai.LLM(
        model=model or LOCAL_LLM_MODEL, api_key="not-needed", base_url=LOCAL_LLM_BASE_URL
    )


def resolve_session_config(ctx: JobContext) -> dict:
    """Per-session config from room metadata (set by web_server.py's /token
    endpoint: LLM provider/key, custom personality instructions, and an
    optional knowledge-base URL for RAG), falling back to defaults.
    """
    cfg = {
        "provider": DEFAULT_LLM_PROVIDER,
        "api_key": None,
        "model": None,
        "base_url": None,
        "instructions": DEFAULT_INSTRUCTIONS,
        "kb_id": None,
        "kb_url": None,
        "whisper_model": DEFAULT_WHISPER_MODEL,
        "tts_voice": DEFAULT_TTS_VOICE,
    }
    metadata = ctx.room.metadata
    if metadata:
        try:
            meta = json.loads(metadata)
            cfg["provider"] = meta.get("llm_provider", cfg["provider"])
            cfg["api_key"] = meta.get("llm_api_key") or None
            cfg["model"] = meta.get("llm_model") or None
            cfg["base_url"] = meta.get("llm_base_url") or None
            cfg["instructions"] = meta.get("agent_instructions") or DEFAULT_INSTRUCTIONS
            cfg["kb_id"] = meta.get("kb_id") or None
            cfg["kb_url"] = meta.get("kb_url") or None
            cfg["whisper_model"] = meta.get("whisper_model") or DEFAULT_WHISPER_MODEL
            cfg["tts_voice"] = meta.get("tts_voice") or DEFAULT_TTS_VOICE
        except (json.JSONDecodeError, AttributeError):
            logger.warning("could not parse room metadata as JSON: %r", metadata)
    return cfg


class Assistant(Agent):
    """Agent whose persona (instructions) and knowledge (RAG index) are set
    per-session from the web UI's settings panel.
    """

    def __init__(self, *, instructions: str, rag_index: rag.SimpleTfidfIndex | None, kb_url: str | None) -> None:
        super().__init__(instructions=instructions)
        self._rag_index = rag_index
        self._kb_url = kb_url

    async def on_user_turn_completed(
        self, turn_ctx: llm_types.ChatContext, new_message: llm_types.ChatMessage
    ) -> None:
        if not self._rag_index:
            return
        query = new_message.text_content or ""
        chunks = self._rag_index.query(query, k=RAG_TOP_K)
        if not chunks:
            return
        context = "\n---\n".join(chunks)
        turn_ctx.add_message(
            role="system",
            content=(
                f"Reference material scraped from {self._kb_url}. Use it to answer "
                f"the user's question if relevant; ignore it otherwise:\n\n{context}"
            ),
        )
        logger.info("RAG: injected %d chunk(s) for query %r", len(chunks), query[:80])


def prewarm(proc: agents.JobProcess) -> None:
    proc.userdata["vad"] = silero.VAD.load()


async def entrypoint(ctx: JobContext) -> None:
    await ctx.connect()

    cfg = resolve_session_config(ctx)
    try:
        llm = build_llm(cfg["provider"], cfg["api_key"], cfg["model"], cfg["base_url"])
        logger.info(
            "using LLM provider=%s model=%s base_url=%s",
            cfg["provider"],
            cfg["model"] or "(default)",
            cfg["base_url"] or "(default)",
        )
    except ValueError as e:
        logger.warning("%s -- falling back to local LLM", e)
        llm = build_llm("local")

    rag_index = None
    if cfg["kb_id"]:
        kb = rag.load_kb(cfg["kb_id"])
        if kb and kb.get("chunks"):
            rag_index = rag.SimpleTfidfIndex(kb["chunks"])
            logger.info("RAG: loaded %d chunk(s) from %s", len(kb["chunks"]), cfg["kb_url"])
        else:
            logger.warning("RAG: kb_id %s not found in cache", cfg["kb_id"])

    whisper_model = resolve_whisper_model(cfg["whisper_model"])
    piper_model = resolve_piper_voice(cfg["tts_voice"])
    logger.info("using whisper_model=%s tts_voice=%s", cfg["whisper_model"], cfg["tts_voice"])

    session = AgentSession(
        vad=ctx.proc.userdata["vad"],
        stt=FasterWhisperSTT(model_size=whisper_model),
        llm=llm,
        tts=PiperTTS(piper_bin=PIPER_BIN, model_path=piper_model),
        # VAD-based turn detection: fully local, avoids the framework's default
        # attempt to reach LiveKit Cloud's turn-detector inference endpoint.
        turn_detection="vad",
    )

    await session.start(
        room=ctx.room,
        agent=Assistant(
            instructions=cfg["instructions"], rag_index=rag_index, kb_url=cfg["kb_url"]
        ),
    )

    await session.generate_reply(
        instructions="Greet the user briefly and offer your assistance."
    )


if __name__ == "__main__":
    cli.run_app(
        WorkerOptions(entrypoint_fnc=entrypoint, prewarm_fnc=prewarm)
    )
