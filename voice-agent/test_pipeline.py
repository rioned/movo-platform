"""End-to-end local voice pipeline smoke test: STT -> LLM -> TTS.

Exercises the same models the real LiveKit agent (agent.py) uses, without
needing a LiveKit room / live audio I/O:
  1. faster-whisper transcribes /tmp/test.wav (Piper-generated speech)
  2. the transcript is sent to the local llama-server (OpenAI-compatible)
  3. the reply text is spoken via the Piper binary and saved to reply.wav
"""
import subprocess
import time
import wave
from pathlib import Path

import requests
from faster_whisper import WhisperModel

BASE_DIR = Path(__file__).resolve().parent
PIPER_BIN = BASE_DIR / "piper" / "piper" / "piper"
PIPER_MODEL = BASE_DIR / "models" / "en_US-lessac-medium.onnx"
LLM_URL = "http://localhost:8000/v1/chat/completions"
SAMPLE_RATE = 22050

print("=== 1) STT: faster-whisper transcribing /tmp/test.wav ===")
t0 = time.time()
model = WhisperModel("small.en", device="cpu", compute_type="int8")
segments, info = model.transcribe("/tmp/test.wav", beam_size=1)
transcript = " ".join(seg.text.strip() for seg in segments).strip()
print(f"transcript: {transcript!r}  (lang={info.language}, {time.time() - t0:.2f}s)")

print("\n=== 2) LLM: querying local llama-server ===")
t0 = time.time()
resp = requests.post(
    LLM_URL,
    json={
        "model": "qwen2.5-0.5b-instruct",
        "messages": [
            {
                "role": "system",
                "content": "You are a concise voice assistant, reply in one short sentence.",
            },
            {"role": "user", "content": transcript},
        ],
    },
    timeout=60,
)
resp.raise_for_status()
reply_text = resp.json()["choices"][0]["message"]["content"].strip()
print(f"reply: {reply_text!r}  ({time.time() - t0:.2f}s)")

print("\n=== 3) TTS: synthesizing reply with Piper ===")
t0 = time.time()
proc = subprocess.run(
    [str(PIPER_BIN), "--model", str(PIPER_MODEL), "--output-raw"],
    input=(reply_text + "\n").encode("utf-8"),
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
)
pcm_data = proc.stdout
out_path = BASE_DIR / "reply.wav"
with wave.open(str(out_path), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(SAMPLE_RATE)
    w.writeframes(pcm_data)

duration = len(pcm_data) / 2 / SAMPLE_RATE
print(
    f"wrote {out_path} : {out_path.stat().st_size} bytes, "
    f"{duration:.2f}s audio  ({time.time() - t0:.2f}s synth time)"
)

print("\n=== SUMMARY ===")
print(f"STT transcript : {transcript}")
print(f"LLM reply      : {reply_text}")
print(f"TTS output     : {out_path} ({duration:.2f}s)")
