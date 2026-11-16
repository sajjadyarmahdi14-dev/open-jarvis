"""Google Gemini & Cloud REST Speech-to-Text backend.

Uses Google Gemini Multimodal Audio API (or OpenAI Whisper REST API) via pure httpx,
requiring zero local heavy models or complex C++ dependencies on Android / Termux.
"""

from __future__ import annotations

import base64
import logging
import os
from typing import List, Optional

import httpx

from openjarvis.core.registry import SpeechRegistry
from openjarvis.speech._stubs import SpeechBackend, TranscriptionResult

logger = logging.getLogger(__name__)

GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"


@SpeechRegistry.register("gemini")
class GeminiSpeechBackend(SpeechBackend):
    """Zero-extra-key Speech-to-Text using Gemini 2.5 Flash Multimodal Audio."""

    backend_id = "gemini"

    def __init__(
        self,
        api_key: Optional[str] = None,
        model: str = "gemini-2.5-flash",
        timeout: float = 30.0,
    ) -> None:
        self.api_key = api_key or os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or ""
        self.model = model
        self.timeout = timeout

    def health(self) -> bool:
        """True if a valid Gemini API key is present."""
        return bool(self.api_key.strip())

    def supported_formats(self) -> List[str]:
        return ["wav", "mp3", "m4a", "ogg", "aac", "flac"]

    def transcribe(
        self,
        audio: bytes,
        *,
        format: str = "wav",
        language: Optional[str] = None,
    ) -> TranscriptionResult:
        """Transcribe audio bytes using Gemini multimodal audio REST call."""
        if not self.health():
            raise RuntimeError(
                "Gemini STT requires GEMINI_API_KEY or GOOGLE_API_KEY. "
                "Set it in your .env file."
            )
        if not audio:
            return TranscriptionResult(text="", duration_seconds=0.0)

        # Normalize format to mime type
        fmt = format.lower().strip(".")
        mime_map = {
            "wav": "audio/wav",
            "mp3": "audio/mpeg",
            "m4a": "audio/mp4",
            "mp4": "audio/mp4",
            "ogg": "audio/ogg",
            "flac": "audio/flac",
            "aac": "audio/aac",
        }
        mime_type = mime_map.get(fmt, "audio/wav")

        b64_audio = base64.b64encode(audio).decode("utf-8")

        prompt_text = "Transcribe this audio recording accurately into text. Output ONLY the verbatim spoken text, nothing else. Do not add explanations, quotes, punctuation corrections, or conversational filler."
        if language:
            prompt_text += f" Spoken language is '{language}'."

        payload = {
            "contents": [
                {
                    "parts": [
                        {
                            "inline_data": {
                                "mime_type": mime_type,
                                "data": b64_audio,
                            }
                        },
                        {"text": prompt_text},
                    ]
                }
            ],
            "generationConfig": {
                "temperature": 0.0,
                "maxOutputTokens": 1024,
            },
        }

        url = f"https://generativelanguage.googleapis.com/v1beta/models/{self.model}:generateContent?key={self.api_key}"

        try:
            with httpx.Client(timeout=self.timeout) as client:
                resp = client.post(url, json=payload)
                resp.raise_for_status()
                data = resp.json()

            candidates = data.get("candidates", [])
            transcribed_text = ""
            if candidates:
                parts = candidates[0].get("content", {}).get("parts", [])
                text_pieces = [p.get("text", "") for p in parts if p.get("text")]
                transcribed_text = " ".join(text_pieces).strip()

            return TranscriptionResult(
                text=transcribed_text,
                language=language,
                confidence=1.0 if transcribed_text else 0.0,
            )
        except Exception as exc:
            logger.error("Gemini STT failed: %s", exc)
            raise RuntimeError(f"Gemini transcription failed: {exc}") from exc


__all__ = ["GeminiSpeechBackend"]
