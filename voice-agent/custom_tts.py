from __future__ import annotations

import asyncio

from livekit.agents import tts
from livekit.agents.types import DEFAULT_API_CONNECT_OPTIONS, APIConnectOptions

NUM_CHANNELS = 1


class PiperTTS(tts.TTS):
    """Fully local, offline TTS using the Piper CLI binary (subprocess, raw PCM stdout)."""

    def __init__(
        self,
        *,
        piper_bin: str,
        model_path: str,
        sample_rate: int = 22050,
        length_scale: float = 1.0,
    ) -> None:
        super().__init__(
            capabilities=tts.TTSCapabilities(streaming=False),
            sample_rate=sample_rate,
            num_channels=NUM_CHANNELS,
        )
        self._piper_bin = piper_bin
        self._model_path = model_path
        self._length_scale = length_scale

    @property
    def model(self) -> str:
        return "piper"

    @property
    def provider(self) -> str:
        return "local"

    def synthesize(
        self, text: str, *, conn_options: APIConnectOptions = DEFAULT_API_CONNECT_OPTIONS
    ) -> tts.ChunkedStream:
        return PiperChunkedStream(tts=self, input_text=text, conn_options=conn_options)


class PiperChunkedStream(tts.ChunkedStream):
    def __init__(self, *, tts: PiperTTS, input_text: str, conn_options: APIConnectOptions) -> None:
        super().__init__(tts=tts, input_text=input_text, conn_options=conn_options)
        self._tts: PiperTTS = tts

    async def _run(self, output_emitter: tts.AudioEmitter) -> None:
        proc = await asyncio.create_subprocess_exec(
            self._tts._piper_bin,
            "--model",
            self._tts._model_path,
            "--length_scale",
            str(self._tts._length_scale),
            "--output-raw",
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
        text = (self.input_text.strip() or " ") + "\n"
        stdout_data, _ = await proc.communicate(text.encode("utf-8"))

        output_emitter.initialize(
            request_id=str(id(self)),
            sample_rate=self._tts.sample_rate,
            num_channels=self._tts.num_channels,
            mime_type="audio/pcm",
        )
        output_emitter.push(stdout_data)
        output_emitter.flush()
