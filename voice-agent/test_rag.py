"""Verifies the real RAG code path end to end (no audio/STT involved):
  1. rag.scrape_url() against a live page (or the disk cache from it)
  2. rag.SimpleTfidfIndex retrieval quality on that real content
  3. agent.Assistant.on_user_turn_completed() -- the ACTUAL hook agent.py
     registers with livekit-agents -- correctly injects the retrieved
     chunks into the chat context for a realistic user question.
"""
import asyncio
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from livekit.agents import llm

import rag
import agent as agent_mod

URL = "https://en.wikipedia.org/wiki/Kali_Linux"
QUESTION = "Who develops Kali Linux and what is it used for?"


async def main() -> None:
    print("=== 1) scrape/cache ===")
    kb = rag.scrape_url(URL)
    print(f"kb_id={kb['kb_id']} chunks={len(kb['chunks'])}")

    print("\n=== 2) raw retrieval quality ===")
    index = rag.SimpleTfidfIndex(kb["chunks"])
    hits = index.query(QUESTION, k=3)
    for i, h in enumerate(hits):
        print(f"--- hit {i} ---\n{h[:200]}\n")
    assert hits, "no chunks retrieved for a clearly on-topic question"
    assert any("offensive security" in h.lower() or "penetration" in h.lower() for h in hits), (
        "retrieved chunks don't look relevant to the question"
    )
    print("retrieval OK")

    print("\n=== 3) Assistant.on_user_turn_completed (real agent.py code) ===")
    assistant = agent_mod.Assistant(
        instructions="test", rag_index=index, kb_url=kb["url"]
    )
    turn_ctx = llm.ChatContext()
    turn_ctx.add_message(role="system", content="You are a helpful assistant.")
    new_message = llm.ChatMessage(role="user", content=[QUESTION])

    await assistant.on_user_turn_completed(turn_ctx, new_message)

    items = turn_ctx.items
    injected = [
        it for it in items if getattr(it, "role", None) == "system" and "Reference material" in (it.text_content or "")
    ]
    assert injected, "on_user_turn_completed did not inject a reference-material system message"
    print("injected system message:")
    print(injected[0].text_content[:400])
    print("\n=== ALL RAG WIRING CHECKS PASSED ===")


if __name__ == "__main__":
    asyncio.run(main())
