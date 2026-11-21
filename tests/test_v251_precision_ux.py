"""Test suite for OpenJarvis v2.5.1-android Precision UX & Audio Upgrades.

Verifies:
1. Speech-Band Band-Pass Filter (300Hz-3400Hz) attenuates low rumble and high hiss.
2. Peak normalization and bandpass filtering in _normalize_audio.
3. Background-first execution policy in CORE_SYSTEM_PROMPT.
4. STT endpoint handling with gentle speech-band normalization.
"""

import io
import math
import struct
import wave
import pytest
from fastapi.testclient import TestClient

from openjarvis.web.server import (
    app,
    CORE_SYSTEM_PROMPT,
    _speech_bandpass_filter,
    _normalize_audio
)


@pytest.fixture
def client():
    return TestClient(app)


def test_core_system_prompt_background_first_policy():
    """Verify that CORE_SYSTEM_PROMPT enforces background-first silent execution."""
    assert "BACKGROUND-FIRST POLICY" in CORE_SYSTEM_PROMPT
    assert "natively and silently in the background" in CORE_SYSTEM_PROMPT
    assert "without launching external OEM apps" in CORE_SYSTEM_PROMPT


def test_speech_bandpass_filter_attenuates_rumble_and_hiss():
    """Verify 300Hz-3400Hz speech bandpass filter attenuates <300Hz and >3400Hz."""
    framerate = 16000
    count = 1600  # 100ms

    # 1. 100 Hz rumble
    rumble = [int(math.sin(2.0 * math.pi * 100.0 * i / framerate) * 10000.0) for i in range(count)]
    # 2. 1000 Hz human voice formant
    voice = [int(math.sin(2.0 * math.pi * 1000.0 * i / framerate) * 10000.0) for i in range(count)]
    # 3. 6000 Hz high cymbal hiss
    hiss = [int(math.sin(2.0 * math.pi * 6000.0 * i / framerate) * 10000.0) for i in range(count)]

    filtered_rumble = _speech_bandpass_filter(rumble, framerate)
    filtered_voice = _speech_bandpass_filter(voice, framerate)
    filtered_hiss = _speech_bandpass_filter(hiss, framerate)

    rms_rumble = math.sqrt(sum(s * s for s in filtered_rumble) / count)
    rms_voice = math.sqrt(sum(s * s for s in filtered_voice) / count)
    rms_hiss = math.sqrt(sum(s * s for s in filtered_hiss) / count)

    # 1000 Hz voice formant must pass through with significantly higher energy than rumble or hiss
    assert rms_voice > rms_rumble * 2.0, f"Voice ({rms_voice}) must exceed rumble ({rms_rumble})"
    assert rms_voice > rms_hiss * 1.5, f"Voice ({rms_voice}) must exceed hiss ({rms_hiss})"


def test_normalize_audio_peak_normalization_and_wav_generation():
    """Verify _normalize_audio normalizes quiet speech and outputs a valid WAV file."""
    framerate = 16000
    count = 1600  # 100ms
    # Quiet 1000 Hz tone with peak 1200
    quiet_samples = [int(math.sin(2.0 * math.pi * 1000.0 * i / framerate) * 1200.0) for i in range(count)]

    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(framerate)
        w.writeframes(struct.pack(f"<{count}h", *quiet_samples))
    raw_wav = buf.getvalue()

    norm_wav, duration, rms = _normalize_audio(raw_wav)
    assert duration == pytest.approx(0.1, abs=0.01)
    assert rms > 0

    # Read back normalized WAV and inspect boosted peak
    with wave.open(io.BytesIO(norm_wav), "rb") as w:
        frames = w.readframes(w.getnframes())
        shorts = struct.unpack(f"<{len(frames) // 2}h", frames)
        max_peak = max(abs(s) for s in shorts)
        # Peak should be boosted close to ~24000
        assert max_peak > 15000, f"Expected boosted peak > 15000, got {max_peak}"


def test_stt_endpoint_with_normalized_audio(client):
    """Verify POST /api/stt accepts valid WAV and returns response without throwing NoneType."""
    framerate = 16000
    count = 800
    samples = [int(math.sin(2.0 * math.pi * 400.0 * i / framerate) * 5000.0) for i in range(count)]
    buf = io.BytesIO()
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(framerate)
        w.writeframes(struct.pack(f"<{count}h", *samples))
    raw_wav = buf.getvalue()

    resp = client.post("/api/stt", data=raw_wav, headers={"Content-Type": "audio/wav"})
    assert resp.status_code == 200
    data = resp.json()
    assert "transcript" in data or "text" in data
