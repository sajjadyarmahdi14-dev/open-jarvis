"""Android Native Text-to-Speech backend using Termux:API.

Speaks text directly via Android OS speech engines with zero latency,
offline operation, and no heavy neural model dependencies.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, List

from openjarvis.core.registry import TTSRegistry
from openjarvis.speech.tts import TTSBackend, TTSResult

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = 10.0


@TTSRegistry.register("android_tts")
class AndroidTTS(TTSBackend):
    """Native Android TTS using `termux-tts-speak`."""

    backend_id = "android_tts"

    def __init__(self, timeout: float = DEFAULT_TIMEOUT) -> None:
        self.timeout = float(os.environ.get("TERMUX_API_TIMEOUT", timeout))

    def health(self) -> bool:
        """Check if termux-tts-speak is available on PATH."""
        return shutil.which("termux-tts-speak") is not None

    def available_voices(self) -> List[str]:
        """Query available TTS engines and engines/voices installed on Android."""
        if not shutil.which("termux-tts-info"):
            return ["default"]
        try:
            res = subprocess.run(
                ["termux-tts-info"],
                capture_output=True,
                text=True,
                timeout=min(self.timeout, 5.0),
                check=False,
            )
            if res.returncode == 0 and res.stdout.strip():
                import json

                data = json.loads(res.stdout)
                if isinstance(data, list):
                    return [item.get("name", "default") for item in data if isinstance(item, dict)]
        except Exception as exc:
            logger.debug("Failed to query termux-tts-info: %s", exc)
        return ["default"]

    def synthesize(
        self,
        text: str,
        *,
        voice_id: str = "",
        speed: float = 1.0,
        output_format: str = "wav",
    ) -> TTSResult:
        """Speak the text aloud via Termux:API.

        Returns an empty audio payload since Android TTS speaks directly through
        the device audio hardware.
        """
        if not self.health():
            raise RuntimeError(
                "termux-tts-speak binary not found. Install Termux:API package "
                "(pkg install termux-api) and the Termux:API Android app."
            )

        cmd = ["termux-tts-speak"]
        if speed > 0:
            cmd.extend(["-r", str(round(speed, 2))])
        if voice_id and voice_id != "default":
            cmd.extend(["-e", voice_id])
        cmd.append(text)

        try:
            subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=max(self.timeout, len(text) * 0.15 + 5.0),
                check=True,
            )
        except subprocess.TimeoutExpired as exc:
            logger.warning("termux-tts-speak timed out after %ss", self.timeout)
            raise RuntimeError(
                f"termux-tts-speak timed out after {self.timeout}s. "
                "Ensure the Termux:API companion app has background and audio permissions."
            ) from exc
        except subprocess.CalledProcessError as exc:
            logger.warning("termux-tts-speak failed: %s", exc.stderr)
            raise RuntimeError(f"termux-tts-speak error: {exc.stderr.strip()}") from exc

        return TTSResult(
            audio=b"",
            format="none",
            duration_seconds=len(text) * 0.05,
            voice_id=voice_id or "default",
            metadata={"engine": "termux-tts-speak", "direct_playback": True},
        )


__all__ = ["AndroidTTS"]
