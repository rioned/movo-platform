"""A livekit-agents LLM plugin backed by the Hermes Agent CLI (Nous
Research's autonomous agent framework, already installed on this machine
at ~/.local/bin/hermes) instead of a direct chat-completions API call.

Wiring: LiveKit still drives the whole pipeline (STT -> [this] -> TTS).
Each user turn's transcript is handed to `hermes chat -q "<text>" -Q`
(quiet, non-interactive single-query mode) as a subprocess; the CLI's
stdout is the plain response text (session-id/warnings go to stderr, so
parsing is trivial). Conversation continuity across turns within one
voice session is kept by resuming the same Hermes session id
(`--resume <id>`), captured from stderr on the first call.

Hermes is a full agentic loop (tool use, skills, memory) rather than a
bare LLM, so a turn typically takes several seconds -- much slower than
calling llama-server or DeepSeek directly. It's offered as an explicit,
opt-in provider for that reason.
"""
from __future__ import annotations

import asyncio
import logging
import os

from livekit.agents import APIConnectionError, APITimeoutError, llm
from livekit.agents.types import DEFAULT_API_CONNECT_OPTIONS, APIConnectOptions

logger = logging.getLogger("hermes-llm")

DEFAULT_HERMES_BIN = os.path.expanduser("~/.local/bin/hermes")
RAG_MARKER = "Reference material scraped from"

# Hermes is a full autonomous coding/tool-use agent -- it must NEVER be
# spawned with this project's directory as its cwd/working scope, or it can
# (and, once, did) "helpfully" rewrite files here like .env. Give every
# invocation its own inert scratch directory, both via the OS-level subprocess
# cwd and Hermes's own `--in` flag (belt and suspenders).
DEFAULT_HERMES_WORKDIR = os.path.expanduser("~/.hermes-voice-agent-workdir")


class HermesLLM(llm.LLM):
    def __init__(
        self,
        *,
        hermes_bin: str = DEFAULT_HERMES_BIN,
        timeout: float = 90.0,
        source: str = "tool",
        workdir: str = DEFAULT_HERMES_WORKDIR,
    ) -> None:
        super().__init__()
        self._hermes_bin = hermes_bin
        self._timeout = timeout
        self._source = source
        self._workdir = workdir
        os.makedirs(self._workdir, exist_ok=True)
        self._session_id: str | None = None

    @property
    def model(self) -> str:
        return "hermes-agent"

    @property
    def provider(self) -> str:
        return "hermes"

    def chat(
        self,
        *,
        chat_ctx: llm.ChatContext,
        tools: list[llm.Tool] | None = None,
        conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS,
        parallel_tool_calls=None,
        tool_choice=None,
        extra_kwargs=None,
    ) -> HermesLLMStream:
        return HermesLLMStream(
            self, chat_ctx=chat_ctx, tools=tools or [], conn_options=conn_options
        )


class HermesLLMStream(llm.LLMStream):
    def __init__(
        self,
        hermes_llm: HermesLLM,
        *,
        chat_ctx: llm.ChatContext,
        tools: list[llm.Tool],
        conn_options: APIConnectOptions,
    ) -> None:
        super().__init__(hermes_llm, chat_ctx=chat_ctx, tools=tools, conn_options=conn_options)
        self._hermes = hermes_llm

    def _build_query(self) -> str:
        items = self._chat_ctx.items

        query = ""
        last_user_idx = None
        for i in range(len(items) - 1, -1, -1):
            if getattr(items[i], "role", None) == "user":
                query = items[i].text_content or ""
                last_user_idx = i
                break

        parts: list[str] = []

        # First turn of the session: steer Hermes with the chosen personality
        # (its own system message, normally set from Agent(instructions=...)).
        if not self._hermes._session_id and items:
            first = items[0]
            if getattr(first, "role", None) == "system":
                persona = first.text_content or ""
                if persona:
                    parts.append(f"(Adopt this persona for our conversation: {persona})")

        # RAG context injected by Assistant.on_user_turn_completed for this
        # turn specifically sits immediately before the new user message.
        if last_user_idx is not None and last_user_idx > 0:
            prev = items[last_user_idx - 1]
            if getattr(prev, "role", None) == "system":
                text = prev.text_content or ""
                if text.startswith(RAG_MARKER):
                    parts.append(text)

        parts.append(query)
        return "\n\n".join(p for p in parts if p)

    async def _run(self) -> None:
        query = self._build_query()
        if not query.strip():
            return

        cmd = [
            self._hermes._hermes_bin,
            "chat",
            "-q",
            query,
            "-Q",
            "--source",
            self._hermes._source,
            "--in",
            self._hermes._workdir,
            "--no-restore-cwd",
        ]
        if self._hermes._session_id:
            cmd += ["--resume", self._hermes._session_id]

        try:
            proc = await asyncio.create_subprocess_exec(
                *cmd,
                cwd=self._hermes._workdir,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
            )
        except FileNotFoundError as e:
            raise APIConnectionError(f"hermes binary not found at {self._hermes._hermes_bin}") from e

        try:
            stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=self._hermes._timeout)
        except asyncio.TimeoutError:
            proc.kill()
            await proc.wait()
            raise APITimeoutError() from None

        text = stdout.decode("utf-8", "replace").strip()

        for line in stderr.decode("utf-8", "replace").splitlines():
            line = line.strip()
            if line.startswith("session_id:"):
                self._hermes._session_id = line.split(":", 1)[1].strip()
                logger.info("hermes session_id=%s", self._hermes._session_id)
                break

        if not text:
            stderr_tail = stderr.decode("utf-8", "replace").strip()[-300:]
            logger.warning("hermes returned no stdout; stderr tail: %s", stderr_tail)
            text = "Sorry, I didn't get a response that time."

        await self._event_ch.send(
            llm.ChatChunk(
                id=str(id(self)),
                delta=llm.ChoiceDelta(role="assistant", content=text),
            )
        )
