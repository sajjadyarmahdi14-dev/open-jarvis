"""Tests for voice recording and /api/voice /api/voice/upload endpoints."""

import io
import tempfile
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from openjarvis.speech._stubs import TranscriptionResult
from openjarvis.speech.voice_io import _record_termux
from openjarvis.speech.gemini_stt import GeminiSpeechBackend
from openjarvis.web.server import app


def test_record_termux_signature_compatibility(monkeypatch, tmp_path):
    """Verify _record_termux accepts all combinations of duration, max_seconds, destination_path."""
    fake_audio = b"FAKE_AUDIO_DATA_TERMUX"

    def fake_run(cmd, *args, **kwargs):
        # When recording command is called, write fake audio to output file
        if "-f" in cmd:
            file_idx = cmd.index("-f") + 1
            Path(cmd[file_idx]).write_bytes(fake_audio)
        return MagicMock(return_value=0, stderr="")

    monkeypatch.setattr("subprocess.run", fake_run)
    monkeypatch.setattr("time.sleep", lambda s: None)

    # 1. Called with destination_path and duration keyword (from /api/voice)
    dest_file = tmp_path / "test1.m4a"
    res1 = _record_termux(dest_file, duration=4.0)
    assert res1 == fake_audio
    assert dest_file.read_bytes() == fake_audio

    # 2. Called with max_seconds keyword (from voice_io)
    res2 = _record_termux(max_seconds=5.0)
    assert res2 == fake_audio

    # 3. Called with duration keyword only
    res3 = _record_termux(duration=3.0)
    assert res3 == fake_audio

    # 4. Called with positional float duration
    res4 = _record_termux(2.5)
    assert res4 == fake_audio

    # 5. Called with no arguments
    res5 = _record_termux()
    assert res5 == fake_audio


def test_gemini_stt_transcription_result_string_operations():
    result = TranscriptionResult(text="  Hello Jarvis!  ")
    # Supports str(), .strip(), and .text
    assert str(result) == "  Hello Jarvis!  "
    assert result.strip() == "Hello Jarvis!"
    assert result.text == "  Hello Jarvis!  "


def test_gemini_stt_supports_webm_and_path(tmp_path, monkeypatch):
    stt = GeminiSpeechBackend(api_key="fake-test-key")
    assert "webm" in stt.supported_formats()

    # Create dummy audio file
    dummy_file = tmp_path / "test.webm"
    dummy_file.write_bytes(b"dummy-audio-bytes")

    fake_response = MagicMock()
    fake_response.json.return_value = {
        "candidates": [
            {"content": {"parts": [{"text": "transcribed speech from webm"}]}}
        ]
    }
    fake_response.raise_for_status = MagicMock()

    with patch("httpx.Client.post", return_value=fake_response):
        # 1. Transcribe bytes
        res1 = stt.transcribe(b"dummy-bytes", format="webm")
        assert res1.text == "transcribed speech from webm"

        # 2. Transcribe Path
        res2 = stt.transcribe(dummy_file)
        assert res2.text == "transcribed speech from webm"


def test_api_voice_endpoint(monkeypatch):
    client = TestClient(app)

    # Mock _record_termux to succeed
    monkeypatch.setattr(
        "openjarvis.speech.voice_io._record_termux",
        lambda *args, **kwargs: b"recorded-audio-bytes"
    )

    # Mock GeminiSpeechBackend transcribe
    mock_stt_res = TranscriptionResult(text="turn on flashlight")
    monkeypatch.setattr(
        "openjarvis.speech.gemini_stt.GeminiSpeechBackend.transcribe",
        lambda *args, **kwargs: mock_stt_res
    )

    # Mock chat_endpoint
    with patch("openjarvis.web.server.chat_endpoint") as mock_chat:
        mock_chat.return_value = {
            "response": "Flashlight turned on.",
            "tool_calls": [{"name": "android_torch", "arguments": {"enabled": True}}]
        }

        resp = client.post("/api/voice", json={"tts": False})
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("transcription") == "turn on flashlight"
        assert data.get("response") == "Flashlight turned on."
        assert len(data.get("tool_calls")) == 1


def test_api_voice_upload_endpoint(monkeypatch):
    client = TestClient(app)

    # Mock GeminiSpeechBackend transcribe
    mock_stt_res = TranscriptionResult(text="battery status")
    monkeypatch.setattr(
        "openjarvis.speech.gemini_stt.GeminiSpeechBackend.transcribe",
        lambda *args, **kwargs: mock_stt_res
    )

    with patch("openjarvis.web.server.chat_endpoint") as mock_chat:
        mock_chat.return_value = {
            "response": "Battery is at 85%.",
            "tool_calls": [{"name": "android_battery", "arguments": {}}]
        }

        audio_file = io.BytesIO(b"fake-webm-audio-stream")
        resp = client.post(
            "/api/voice/upload",
            files={"audio": ("voice.webm", audio_file, "audio/webm")},
            data={"tts": "false"}
        )
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("transcription") == "battery status"
        assert data.get("response") == "Battery is at 85%."
        assert len(data.get("tool_calls")) == 1


def test_api_voice_upload_empty():
    client = TestClient(app)
    empty_file = io.BytesIO(b"")
    resp = client.post(
        "/api/voice/upload",
        files={"audio": ("empty.webm", empty_file, "audio/webm")},
        data={"tts": "false"}
    )
    assert resp.status_code == 200
    data = resp.json()
    assert "error" in data
