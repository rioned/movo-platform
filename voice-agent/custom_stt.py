from __future__ import annotations

import asyncio
import io

from faster_whisper import WhisperModel
from livekit import rtc
from livekit.agents import APIConnectOptions, stt
from livekit.agents.types import NOT_GIVEN, NotGivenOr
from livekit.agents.utils import AudioBuffer, is_given


class FasterWhisperSTT(stt.STT):
    """Fully local, offline STT using faster-whisper (CTranslate2, CPU)."""

    def __init__(
        self,
        *,
        model_size: str = "small.en",
        device: str = "cpu",
        compute_type: str = "int8",
        language: str = "en",
    ) -> None:
        super().__init__(
            capabilities=stt.STTCapabilities(streaming=False, interim_results=False)
        )
        self._language = language
        self._model = WhisperModel(model_size, device=device, compute_type=compute_type)
        self._lock = asyncio.Lock()

    @property
    def model(self) -> str:
        return "faster-whisper"

    @property
    def provider(self) -> str:
        return "local"

    async def _recognize_impl(
        self,
        buffer: AudioBuffer,
        *,
        language: NotGivenOr[str] = NOT_GIVEN,
        conn_options: APIConnectOptions,
    ) -> stt.SpeechEvent:
        wav_bytes = rtc.combine_audio_frames(buffer).to_wav_bytes()
        lang = language if is_given(language) else self._language

        loop = asyncio.get_running_loop()

        def _transcribe() -> str:
            segments, _info = self._model.transcribe(
                io.BytesIO(wav_bytes),
                language=lang or None,
                beam_size=1,
                vad_filter=False,
            )
            return " ".join(seg.text.strip() for seg in segments).strip()

        async with self._lock:
            text = await loop.run_in_executor(None, _transcribe)

        return stt.SpeechEvent(
            type=stt.SpeechEventType.FINAL_TRANSCRIPT,
            alternatives=[stt.SpeechData(language=lang or "en", text=text)],
        )
