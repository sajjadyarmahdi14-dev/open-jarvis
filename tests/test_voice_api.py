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


def test_api_tts_audio_endpoint(monkeypatch):
    """Verify neural British and Persian voice synthesis endpoints."""
    client = TestClient(app)
    fake_mp3 = b"ID3\x03\x00\x00\x00FAKE_MP3_STREAM_BRITISH_VOICE_DATA"

    recorded_voices = []

    class FakeCommunicate:
        def __init__(self, text, voice, rate="+5%", pitch="-2Hz"):
            self.text = text
            self.voice = voice
            recorded_voices.append(voice)

        async def stream(self):
            yield {"type": "audio", "data": fake_mp3}

    monkeypatch.setattr("edge_tts.Communicate", FakeCommunicate)

    # 1. English POST endpoint (selects en-GB-RyanNeural)
    resp1 = client.post("/api/tts/audio", json={"text": "Hello sir, all systems nominal."})
    assert resp1.status_code == 200
    assert resp1.headers["content-type"] == "audio/mpeg"
    assert resp1.content == fake_mp3
    assert recorded_voices[-1] == "en-GB-RyanNeural"

    # 2. Persian POST endpoint (selects fa-IR-FaridNeural)
    resp2 = client.post("/api/tts/audio", json={"text": "سلام قربان، آماده خدمت هستم."})
    assert resp2.status_code == 200
    assert resp2.headers["content-type"] == "audio/mpeg"
    assert resp2.content == fake_mp3
    assert recorded_voices[-1] == "fa-IR-FaridNeural"

    # 3. GET endpoint for HTML audio streaming
    resp3 = client.get("/api/tts/audio?text=At+your+service+sir")
    assert resp3.status_code == 200
    assert resp3.headers["content-type"] == "audio/mpeg"
    assert resp3.content == fake_mp3


def test_client_actions_in_chat_and_tools():
    """Verify structured client_actions are generated for Android app launch."""
    import importlib
    import openjarvis.tools.android_tools
    importlib.reload(openjarvis.tools.android_tools)

    client = TestClient(app)

    # Test direct tool execution for android_launch_app
    resp = client.post("/api/tools/android_launch_app", json={"app": "Telegram"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["success"] is True
    assert "client_actions" in data
    actions = data["client_actions"]
    assert len(actions) == 1
    assert actions[0]["type"] == "launch_app"
    assert "Telegram" in actions[0]["target"] or "telegram" in actions[0]["package"].lower()


def test_fast_path_and_clean_tts():
    """Verify <150ms instant local fast-path and <think> token stripping."""
    client = TestClient(app)

    # 1. English fast path app launch
    resp1 = client.post("/api/chat", json={"message": "open camera"})
    assert resp1.status_code == 200
    d1 = resp1.json()
    assert d1.get("fast_path") is True
    assert "Opening camera" in d1["response"]
    assert d1["client_actions"][0]["type"] == "launch_app"

    # 2. Persian fast path app launch
    resp2 = client.post("/api/chat", json={"message": "دوربین رو باز کن"})
    assert resp2.status_code == 200
    d2 = resp2.json()
    assert d2.get("fast_path") is True
    assert "در حال باز کردن" in d2["response"]

    # 3. Flashlight fast path
    resp3 = client.post("/api/chat", json={"message": "turn on flashlight"})
    assert resp3.status_code == 200
    d3 = resp3.json()
    assert d3.get("fast_path") is True
    assert "Flashlight" in d3["response"]

    # 4. Battery fast path
    resp4 = client.post("/api/chat", json={"message": "battery status"})
    assert resp4.status_code == 200
    d4 = resp4.json()
    assert d4.get("fast_path") is True
    assert "Battery" in d4["response"]

    # 5. Clean reasoning blocks <think>...</think>
    from openjarvis.web.server import _clean_tts_response
    raw = "<think>Let me think carefully about this...</think>Hello, sir. How can I help you today?"
    cleaned = _clean_tts_response(raw)
    assert cleaned == "Hello, sir. How can I help you today?"
    assert "<think>" not in cleaned

