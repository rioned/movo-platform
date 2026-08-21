"""Tests the actual livekit-agents plugin classes used by agent.py:
custom_stt.FasterWhisperSTT and custom_tts.PiperTTS, exercised through
their real STT/TTS interfaces (recognize() / synthesize()+collect()),
plus a sanity check that the LLM plugin (openai.LLM against llama-server)
answers through the livekit-agents LLM interface too.
"""
import asyncio
import os
import sys
import time
import wave

from livekit import rtc
from livekit.agents.types import DEFAULT_API_CONNECT_OPTIONS
from livekit.plugins import openai

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from custom_stt import FasterWhisperSTT  # noqa: E402
from custom_tts import PiperTTS  # noqa: E402

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
PIPER_BIN = os.path.join(BASE_DIR, "piper", "piper", "piper")
PIPER_MODEL = os.path.join(BASE_DIR, "models", "en_US-lessac-medium.onnx")


def load_wav_as_frame(path: str) -> rtc.AudioFrame:
    with wave.open(path, "rb") as w:
        num_channels = w.getnchannels()
        sample_rate = w.getframerate()
        data = w.readframes(w.getnframes())
    return rtc.AudioFrame(
        data=data,
        sample_rate=sample_rate,
        num_channels=num_channels,
        samples_per_channel=len(data) // (2 * num_channels),
    )


async def test_stt() -> str:
    print("=== STT plugin: FasterWhisperSTT.recognize() ===")
    stt = FasterWhisperSTT(model_size="small.en")
    frame = load_wav_as_frame("/tmp/test.wav")
    t0 = time.time()
    event = await stt.recognize([frame], conn_options=DEFAULT_API_CONNECT_OPTIONS)
    text = event.alternatives[0].text
    print(f"  type={event.type}  text={text!r}  ({time.time() - t0:.2f}s)")
    assert text.strip(), "STT returned empty transcript"
    assert "piper" in text.lower() or "test" in text.lower(), f"unexpected transcript: {text!r}"
    print("  OK")
    return text


async def test_llm(user_text: str) -> str:
    print("\n=== LLM plugin: openai.LLM against local llama-server ===")
    from livekit.agents import llm as llm_mod

    llm = openai.LLM(
        model="qwen2.5-0.5b-instruct",
        api_key="not-needed",
        base_url="http://localhost:8000/v1",
    )
    chat_ctx = llm_mod.ChatContext()
    chat_ctx.add_message(
        role="system", content="You are a concise voice assistant, reply in one short sentence."
    )
    chat_ctx.add_message(role="user", content=user_text)

    t0 = time.time()
    reply_chunks = []
    async with llm.chat(chat_ctx=chat_ctx) as stream:
        async for chunk in stream:
            if chunk.delta and chunk.delta.content:
                reply_chunks.append(chunk.delta.content)
    reply = "".join(reply_chunks).strip()
    print(f"  reply={reply!r}  ({time.time() - t0:.2f}s)")
    assert reply, "LLM returned empty reply"
    print("  OK")
    await llm.aclose()
    return reply


async def test_tts(text: str) -> None:
    print("\n=== TTS plugin: PiperTTS.synthesize().collect() ===")
    tts = PiperTTS(piper_bin=PIPER_BIN, model_path=PIPER_MODEL)
    t0 = time.time()
    stream = tts.synthesize(text)
    frame = await stream.collect()
    dur = frame.samples_per_channel / frame.sample_rate
    print(
        f"  sample_rate={frame.sample_rate} channels={frame.num_channels} "
        f"duration={dur:.2f}s  ({time.time() - t0:.2f}s synth)"
    )
    assert dur > 0.3, f"TTS produced suspiciously short audio: {dur:.2f}s"

    out_path = os.path.join(BASE_DIR, "reply_plugin.wav")
    with wave.open(out_path, "wb") as w:
        w.setnchannels(frame.num_channels)
        w.setsampwidth(2)
        w.setframerate(frame.sample_rate)
        w.writeframes(bytes(frame.data))
    print(f"  wrote {out_path} ({os.path.getsize(out_path)} bytes)")
    print("  OK")


async def main() -> None:
    transcript = await test_stt()
    reply = await test_llm(transcript)
    await test_tts(reply)
    print("\n=== ALL PLUGIN TESTS PASSED ===")
    print(f"STT transcript : {transcript}")
    print(f"LLM reply      : {reply}")


if __name__ == "__main__":
    asyncio.run(main())
