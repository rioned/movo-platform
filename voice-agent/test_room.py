"""Real end-to-end test: join the actual LiveKit room as a client and verify
the running agent.py worker gets dispatched, generates a greeting via the
local LLM, synthesizes it with Piper, and publishes real audio back over
WebRTC — the one thing test_pipeline.py / test_plugins.py don't cover.

Requires: livekit-server on :7880 and `agent.py dev` already running.
"""
import asyncio
import os
import time
import wave

from livekit import api, rtc

LIVEKIT_URL = "ws://localhost:7880"
API_KEY = "devkey"
API_SECRET = "secret"
ROOM_NAME = f"test-room-{int(time.time())}"
BASE_DIR = os.path.dirname(os.path.abspath(__file__))
OUT_PATH = os.path.join(BASE_DIR, "room_reply.wav")

TIMEOUT_S = 40
RECORD_S = 8


def make_token() -> str:
    grants = api.VideoGrants(room_join=True, room=ROOM_NAME, can_publish=True, can_subscribe=True)
    return (
        api.AccessToken(API_KEY, API_SECRET)
        .with_identity("test-client")
        .with_name("test-client")
        .with_grants(grants)
        .to_jwt()
    )


async def main() -> None:
    room = rtc.Room()
    got_track = asyncio.Event()
    frames: list[rtc.AudioFrame] = []
    audio_stream_holder: dict = {}

    @room.on("track_subscribed")
    def on_track_subscribed(track, publication, participant):
        if track.kind == rtc.TrackKind.KIND_AUDIO:
            print(f"[client] subscribed to audio track from {participant.identity}")
            stream = rtc.AudioStream(track)
            audio_stream_holder["stream"] = stream
            got_track.set()

    print(f"[client] connecting to {LIVEKIT_URL} room={ROOM_NAME} ...")
    await room.connect(LIVEKIT_URL, make_token())
    print(f"[client] connected as {room.local_participant.identity}")

    try:
        await asyncio.wait_for(got_track.wait(), timeout=TIMEOUT_S)
    except asyncio.TimeoutError:
        print("[client] FAILED: no audio track received from agent within timeout")
        await room.disconnect()
        raise SystemExit(1)

    stream = audio_stream_holder["stream"]
    print(f"[client] recording up to {RECORD_S}s of agent audio ...")
    t0 = time.time()
    async for event in stream:
        frames.append(event.frame)
        if time.time() - t0 > RECORD_S:
            break
    await stream.aclose()
    await room.disconnect()

    if not frames:
        print("[client] FAILED: track subscribed but no frames received")
        raise SystemExit(1)

    sample_rate = frames[0].sample_rate
    num_channels = frames[0].num_channels
    pcm = b"".join(bytes(f.data) for f in frames)
    with wave.open(OUT_PATH, "wb") as w:
        w.setnchannels(num_channels)
        w.setsampwidth(2)
        w.setframerate(sample_rate)
        w.writeframes(pcm)

    duration = len(pcm) / 2 / num_channels / sample_rate
    print(f"[client] wrote {OUT_PATH}: {os.path.getsize(OUT_PATH)} bytes, {duration:.2f}s audio")
    assert duration > 0.5, f"received suspiciously little audio: {duration:.2f}s"
    print("\n=== ROOM ROUND-TRIP TEST PASSED ===")


if __name__ == "__main__":
    asyncio.run(main())
