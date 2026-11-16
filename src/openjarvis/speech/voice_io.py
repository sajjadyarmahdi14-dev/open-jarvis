"""Microphone recording and audio playback helpers.

Supports both desktop sounddevice streams and native Android/Termux:API
background recording and media playback with lifecycle guards.
"""

from __future__ import annotations

import io
import logging
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
import wave
from pathlib import Path
from typing import Any, Optional, Union

logger = logging.getLogger(__name__)

_SAMPLE_RATE = 16000
_CHANNELS = 1
_CHUNK = 1024
_SILENCE_THRESHOLD = 500  # RMS below this → silence
_SILENCE_SECONDS = 1.5  # seconds of silence before auto-stop
_STARTUP_SILENCE_SECONDS = 5.0  # give up early if speech never begins
_MAX_RECORD_SECONDS = 30  # safety ceiling


def _is_termux_api_available() -> bool:
    return shutil.which("termux-microphone-record") is not None


def _rms(data: bytes) -> float:
    """Compute RMS amplitude of 16-bit PCM bytes."""
    import struct

    n = len(data) // 2
    if n == 0:
        return 0.0
    shorts = struct.unpack(f"{n}h", data[: n * 2])
    return (sum(s * s for s in shorts) / n) ** 0.5


def _record_termux(
    destination_path: Optional[Union[str, Path, float, int]] = None,
    duration: Optional[float] = None,
    max_seconds: Optional[float] = None,
    interactive: Optional[bool] = None,
    **kwargs: Any,
) -> bytes:
    """Record audio via Termux:API with strict background lifecycle control.

    Termux quirk: `termux-microphone-record -f <file>` spawns an asynchronous
    Android background service and exits immediately. This function properly
    manages starting, interactive push-to-talk stopping (-q), file flushing,
    and cleanup.
    """
    target_path: Optional[Path] = None
    target_duration: Optional[float] = None

    if isinstance(destination_path, (int, float)):
        target_duration = float(destination_path)
    elif destination_path is not None:
        target_path = Path(destination_path)

    if duration is not None:
        target_duration = float(duration)
    elif max_seconds is not None:
        target_duration = float(max_seconds)
    elif "max_duration" in kwargs:
        target_duration = float(kwargs["max_duration"])

    if target_duration is None:
        target_duration = float(_MAX_RECORD_SECONDS)

    # 1. Clean up any dangling recording process from previous aborted runs
    try:
        subprocess.run(
            ["termux-microphone-record", "-q"],
            capture_output=True,
            timeout=5.0,
            check=False,
        )
    except Exception:
        pass

    temp_dir = Path(tempfile.gettempdir())
    temp_file = temp_dir / f"openjarvis_rec_{uuid.uuid4().hex[:8]}.m4a"

    # 2. Start recording to temporary audio container
    record_limit = max(1, int(round(target_duration)))
    cmd = [
        "termux-microphone-record",
        "-f",
        str(temp_file),
        "-l",
        str(record_limit),
    ]
    try:
        subprocess.run(cmd, capture_output=True, text=True, timeout=8.0, check=True)
    except subprocess.TimeoutExpired as exc:
        raise RuntimeError("termux-microphone-record timed out while starting.") from exc
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(f"termux-microphone-record failed: {exc.stderr.strip()}") from exc

    # 3. Push-to-Talk or timed wait
    is_interactive = interactive if interactive is not None else (target_path is None and sys.stdin.isatty())

    if is_interactive:
        sys.stdout.write(" [Listening... Press Enter when done speaking] ")
        sys.stdout.flush()
        try:
            sys.stdin.readline()
        except (KeyboardInterrupt, EOFError):
            pass
    else:
        time.sleep(min(target_duration, 5.0))

    # 4. Explicitly stop recording service
    try:
        subprocess.run(
            ["termux-microphone-record", "-q"],
            capture_output=True,
            timeout=5.0,
            check=False,
        )
    except Exception as exc:
        logger.debug("Failed to stop termux-microphone-record: %s", exc)

    # 5. Flush delay: give Android MediaRecorder 300ms to flush buffers to disk
    time.sleep(0.3)

    if not temp_file.is_file() or temp_file.stat().st_size == 0:
        time.sleep(0.3)

    if not temp_file.is_file() or temp_file.stat().st_size == 0:
        temp_file.unlink(missing_ok=True)
        return b""

    try:
        audio_bytes = temp_file.read_bytes()
        if target_path is not None:
            try:
                target_path.write_bytes(audio_bytes)
            except Exception as write_err:
                logger.warning("Failed to write recorded audio to target path %s: %s", target_path, write_err)
    finally:
        temp_file.unlink(missing_ok=True)

    return audio_bytes


def record_until_silence(
    *,
    sample_rate: int = _SAMPLE_RATE,
    silence_threshold: int = _SILENCE_THRESHOLD,
    silence_seconds: float = _SILENCE_SECONDS,
    startup_silence_seconds: float = _STARTUP_SILENCE_SECONDS,
    max_seconds: float = _MAX_RECORD_SECONDS,
) -> bytes:
    """Record from the microphone until silence is detected or user completes speech.

    On Termux / Android, delegates to `_record_termux()` using `termux-microphone-record`.
    On desktop, uses `sounddevice` with PCM RMS silence detection.
    """
    if _is_termux_api_available():
        return _record_termux(max_seconds=max_seconds)

    try:
        import sounddevice as sd
    except ImportError:
        raise RuntimeError(
            "sounddevice is required for desktop voice input. "
            "Install with: pip install sounddevice (or install termux-api on Android)"
        )

    chunks_per_second = sample_rate / _CHUNK
    silence_chunks = int(silence_seconds * chunks_per_second)
    startup_silence_chunks = max(1, int(startup_silence_seconds * chunks_per_second))
    max_chunks = int(max_seconds * chunks_per_second)

    frames: list[bytes] = []
    silence_count = 0
    has_speech = False

    with sd.RawInputStream(
        samplerate=sample_rate,
        channels=_CHANNELS,
        dtype="int16",
        blocksize=_CHUNK,
    ) as stream:
        for _ in range(max_chunks):
            raw, _ = stream.read(_CHUNK)
            data = bytes(raw)
            frames.append(data)

            amplitude = _rms(data)
            if amplitude > silence_threshold:
                has_speech = True
                silence_count = 0
            elif not has_speech and len(frames) >= startup_silence_chunks:
                break
            elif has_speech:
                silence_count += 1
                if silence_count >= silence_chunks:
                    break

    return _frames_to_wav(frames, sample_rate)


def _frames_to_wav(frames: list[bytes], sample_rate: int) -> bytes:
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(_CHANNELS)
        wf.setsampwidth(2)  # 16-bit
        wf.setframerate(sample_rate)
        wf.writeframes(b"".join(frames))
    return buf.getvalue()


def play_wav(audio: bytes, sample_rate: int = 24000) -> None:
    """Play raw audio bytes through the audio output device.

    Supports `termux-media-player`, CLI media players (`mpv`), and desktop `sounddevice`.
    """
    if not audio:
        return

    # Check for Termux native media player
    if shutil.which("termux-media-player"):
        temp_dir = Path(tempfile.gettempdir())
        temp_file = temp_dir / f"openjarvis_play_{uuid.uuid4().hex[:8]}.wav"
        temp_file.write_bytes(audio)
        try:
            subprocess.run(
                ["termux-media-player", "play", str(temp_file)],
                capture_output=True,
                timeout=15.0,
                check=False,
            )
        finally:
            temp_file.unlink(missing_ok=True)
        return

    # Fall back to desktop sounddevice if available
    try:
        import numpy as np
        import sounddevice as sd
        import soundfile as sf
    except ImportError:
        if shutil.which("mpv"):
            temp_file = Path(tempfile.gettempdir()) / f"openjarvis_play_{uuid.uuid4().hex[:8]}.wav"
            temp_file.write_bytes(audio)
            try:
                subprocess.run(["mpv", "--no-video", str(temp_file)], capture_output=True, timeout=15.0, check=False)
            finally:
                temp_file.unlink(missing_ok=True)
            return

        raise RuntimeError(
            "sounddevice, numpy, and soundfile are required for voice output. "
            "Install with: pip install sounddevice numpy soundfile"
        )

    buf = io.BytesIO(audio)
    try:
        data, sr = sf.read(buf, dtype="float32")
    except Exception:
        import struct

        n = len(audio) // 2
        data = (
            np.array(struct.unpack(f"{n}h", audio[: n * 2]), dtype="float32") / 32768.0
        )
        sr = sample_rate

    sd.play(data, sr)
    sd.wait()


__all__ = ["play_wav", "record_until_silence"]
