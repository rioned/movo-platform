"""Tests HermesLLM through the real livekit-agents LLM interface (.chat()
+ streaming ChatChunks), including session continuity across two turns
(a name given in turn 1 should be recalled in turn 2 via --resume).
"""
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from livekit.agents import llm as llm_mod

from hermes_llm import HermesLLM


async def ask(hermes: HermesLLM, chat_ctx: llm_mod.ChatContext, text: str) -> str:
    chat_ctx.add_message(role="user", content=text)
    t0 = time.time()
    chunks = []
    async with hermes.chat(chat_ctx=chat_ctx) as stream:
        async for chunk in stream:
            if chunk.delta and chunk.delta.content:
                chunks.append(chunk.delta.content)
    reply = "".join(chunks).strip()
    print(f"  ({time.time() - t0:.1f}s) -> {reply!r}")
    chat_ctx.add_message(role="assistant", content=reply)
    return reply


async def main() -> None:
    hermes = HermesLLM()

    chat_ctx = llm_mod.ChatContext()
    chat_ctx.add_message(
        role="system",
        content="You are a concise voice assistant. Keep replies to one short sentence.",
    )

    print("=== turn 1: introduce a name ===")
    r1 = await ask(hermes, chat_ctx, "My name is Zorion. Just say OK.")
    assert r1, "empty reply on turn 1"
    print(f"session_id after turn 1: {hermes._session_id}")
    assert hermes._session_id, "no session_id captured from hermes stderr"

    print("\n=== turn 2: test session continuity ===")
    r2 = await ask(hermes, chat_ctx, "What is my name? Answer with just the name.")
    assert r2, "empty reply on turn 2"
    print(f"session_id after turn 2: {hermes._session_id}")
    assert "zorion" in r2.lower(), f"continuity check failed -- reply did not recall the name: {r2!r}"

    await hermes.aclose()
    print("\n=== ALL HERMES LLM CHECKS PASSED ===")


if __name__ == "__main__":
    asyncio.run(main())
