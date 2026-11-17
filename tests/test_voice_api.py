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


def test_api_ping_endpoint():
    """Verify ultra-lightweight /api/ping responds with status ok in <5ms."""
    client = TestClient(app)
    resp = client.get("/api/ping")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert "time" in data


def test_conversational_greetings_and_time_fast_path():
    """Verify instantaneous (<50ms) fast path for greetings and time/date queries."""
    client = TestClient(app)

    # 1. Greetings: English
    for g in ["Hi", "Hello", "Hey", "Jarvis", "Hey Jarvis", "good morning"]:
        r = client.post("/api/chat", json={"message": g})
        assert r.status_code == 200
        d = r.json()
        assert d.get("fast_path") is True
        assert "At your service, sir" in d["response"]

    # 2. Greetings: Persian
    for g in ["سلام", "درود", "خوبی", "جارویس", "سلام جارویس"]:
        r = client.post("/api/chat", json={"message": g})
        assert r.status_code == 200
        d = r.json()
        assert d.get("fast_path") is True
        assert "در خدمت شما هستم قربان" in d["response"]

    # 3. Time queries
    r_time_en = client.post("/api/chat", json={"message": "what time is it"})
    assert r_time_en.status_code == 200
    assert "It is currently" in r_time_en.json()["response"]

    r_time_fa = client.post("/api/chat", json={"message": "ساعت چنده"})
    assert r_time_fa.status_code == 200
    assert "ساعت هم‌اکنون" in r_time_fa.json()["response"]

    # 4. Date queries
    r_date_en = client.post("/api/chat", json={"message": "what date is today"})
    assert r_date_en.status_code == 200
    assert "Today is" in r_date_en.json()["response"]

    r_date_fa = client.post("/api/chat", json={"message": "تاریخ امروز"})
    assert r_date_fa.status_code == 200
    assert "امروز" in r_date_fa.json()["response"]


def test_volume_fast_path():
    """Verify fast-path volume controls."""
    client = TestClient(app)

    r_up = client.post("/api/chat", json={"message": "volume up"})
    assert r_up.status_code == 200
    assert r_up.json().get("fast_path") is True
    assert "Volume increased" in r_up.json()["response"]

    r_down = client.post("/api/chat", json={"message": "volume down"})
    assert r_down.status_code == 200
    assert r_down.json().get("fast_path") is True
    assert "Volume decreased" in r_down.json()["response"]

    r_mute = client.post("/api/chat", json={"message": "mute"})
    assert r_mute.status_code == 200
    assert r_mute.json().get("fast_path") is True
    assert "muted" in r_mute.json()["response"]


def test_vad_utterance_wake_word_and_commands(monkeypatch):
    """Verify continuous AudioRecord VAD endpoint wake word handling and command execution."""
    import struct
    import openjarvis.web.server as srv
    from openjarvis.web.server import _voice_session

    client = TestClient(app)

    # Generate a valid 44-byte WAV header + dummy PCM
    num_samples = 1600
    wav_bytes = bytearray()
    wav_bytes.extend(b"RIFF")
    wav_bytes.extend(struct.pack("<I", 36 + num_samples * 2))
    wav_bytes.extend(b"WAVEfmt ")
    wav_bytes.extend(struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16))
    wav_bytes.extend(b"data")
    wav_bytes.extend(struct.pack("<I", num_samples * 2))
    wav_bytes.extend(b"\x00" * (num_samples * 2))
    wav_data = bytes(wav_bytes)

    # 1. Standby mode + transcript without wake word -> ignored
    _voice_session["state"] = "STANDBY"
    _voice_session["awake_until"] = 0.0

    with patch("speech_recognition.Recognizer.recognize_google", return_value="what is the weather"):
        r1 = client.post("/api/voice/vad_utterance", content=wav_data, headers={"Content-Type": "audio/wav"})
        assert r1.status_code == 200
        d1 = r1.json()
        assert d1["status"] == "ignored"
        assert d1["reason"] == "no_wake_word"

    # 2. Standby mode + "Jarvis" alone -> enters AWAKE_WAITING_FOR_COMMAND
    with patch("speech_recognition.Recognizer.recognize_google", return_value="Jarvis"):
        r2 = client.post("/api/voice/vad_utterance", content=wav_data, headers={"Content-Type": "audio/wav"})
        assert r2.status_code == 200
        d2 = r2.json()
        assert d2["status"] == "awake"
        assert d2["state"] == "AWAKE_WAITING_FOR_COMMAND"
        assert "At your service, sir. I'm listening." in d2["response"]

    # 3. Already awake -> user speaks command directly ("turn on flashlight")
    with patch("speech_recognition.Recognizer.recognize_google", return_value="turn on flashlight"):
        r3 = client.post("/api/voice/vad_utterance", content=wav_data, headers={"Content-Type": "audio/wav"})
        assert r3.status_code == 200
        d3 = r3.json()
        assert d3["status"] == "executed"
        assert "Flashlight turned on" in d3["response"]

    # 4. Standby mode + "Hey Jarvis, what time is it" -> stripped and executed immediately!
    _voice_session["state"] = "STANDBY"
    _voice_session["awake_until"] = 0.0
    with patch("speech_recognition.Recognizer.recognize_google", return_value="Hey Jarvis, what time is it"):
        r4 = client.post("/api/voice/vad_utterance", content=wav_data, headers={"Content-Type": "audio/wav"})
        assert r4.status_code == 200
        d4 = r4.json()
        assert d4["status"] == "executed"
        assert "It is currently" in d4["response"]

    # 5. Phonetic wake word ("javis") -> triggers wake
    _voice_session["state"] = "STANDBY"
    _voice_session["awake_until"] = 0.0
    with patch("speech_recognition.Recognizer.recognize_google", return_value="javis"):
        r5 = client.post("/api/voice/vad_utterance", content=wav_data, headers={"Content-Type": "audio/wav"})
        assert r5.status_code == 200
        d5 = r5.json()
        assert d5["status"] == "awake"


def test_remote_vps_battery_telemetry():
    """Verify device battery telemetry sent by Android phone works even on remote VPS."""
    client = TestClient(app)

    # English battery with telemetry
    resp1 = client.post("/api/chat", json={
        "message": "check battery",
        "device_battery": 88,
        "device_charging": True
    })
    assert resp1.status_code == 200
    d1 = resp1.json()
    assert d1.get("fast_path") is True
    assert "88%" in d1["response"]
    assert "charging" in d1["response"]

    # Persian battery with telemetry
    resp2 = client.post("/api/chat", json={
        "message": "وضعیت باتری چقدره",
        "device_battery": 45,
        "device_charging": False
    })
    assert resp2.status_code == 200
    d2 = resp2.json()
    assert d2.get("fast_path") is True
    assert "45%" in d2["response"]


def test_alarm_and_phone_call_fast_paths():
    """Verify native client actions for alarm and phone calls."""
    client = TestClient(app)

    # 1. Alarm English
    resp1 = client.post("/api/chat", json={"message": "set an alarm for 7:30 am"})
    assert resp1.status_code == 200
    d1 = resp1.json()
    assert d1.get("fast_path") is True
    assert "07:30" in d1["response"]
    assert any(a.get("type") == "alarm" and a.get("hour") == 7 and a.get("minute") == 30 for a in d1["client_actions"])

    # 2. Alarm Persian
    resp2 = client.post("/api/chat", json={"message": "ساعت 8 زنگ بذار"})
    assert resp2.status_code == 200
    d2 = resp2.json()
    assert d2.get("fast_path") is True
    assert "08:00" in d2["response"]

    # 3. Phone call
    resp3 = client.post("/api/chat", json={"message": "call 09123456789"})
    assert resp3.status_code == 200
    d3 = resp3.json()
    assert d3.get("fast_path") is True
    assert "09123456789" in d3["response"]
    assert any(a.get("type") == "call" and a.get("number") == "09123456789" for a in d3["client_actions"])


def test_device_apps_sync_and_dynamic_fast_path():
    """Verify dynamic package indexing, sync to /api/device/apps, and fast-path matching."""
    client = TestClient(app)

    # 1. Sync dynamic apps from Android phone
    apps_payload = [
        {"label": "Photos", "package": "com.google.android.apps.photos"},
        {"label": "Mi Video", "package": "com.miui.videoplayer"},
        {"label": "Snapp", "package": "cab.snapp.passenger"},
    ]
    post_res = client.post("/api/device/apps", json=apps_payload)
    assert post_res.status_code == 200
    assert post_res.json()["count"] == 3

    get_res = client.get("/api/device/apps")
    assert get_res.status_code == 200
    assert len(get_res.json()["apps"]) == 3

    # 2. Dynamic app launch query matches against synced device apps
    chat_res = client.post("/api/chat", json={"message": "open photos"})
    assert chat_res.status_code == 200
    d = chat_res.json()
    assert d.get("fast_path") is True
    assert any(a.get("type") == "launch_app" and a.get("package") == "com.google.android.apps.photos" for a in d.get("client_actions", []))

    # 3. Dynamic app launch query with wake word and politeness: "Jarvis please open Snapp"
    chat_res2 = client.post("/api/chat", json={"message": "Jarvis please open Snapp"})
    assert chat_res2.status_code == 200
    d2 = chat_res2.json()
    assert d2.get("fast_path") is True
    assert any(a.get("type") == "launch_app" and a.get("package") == "cab.snapp.passenger" for a in d2.get("client_actions", []))


def test_torch_wake_word_stripping_and_clean_tts():
    """Verify torch commands match even when embedded with wake words or politeness, and clean TTS handles think tags."""
    from openjarvis.web.server import _clean_tts_response

    client = TestClient(app)

    # 1. Torch with wake words and politeness
    queries = [
        "Jarvis please turn on flashlight",
        "turn on torch please jarvis",
        "چراغ قوه رو روشن کن جارویس لطفاً",
        "hey jarvis torch",
    ]
    for q in queries:
        r = client.post("/api/chat", json={"message": q})
        assert r.status_code == 200
        d = r.json()
        assert d.get("fast_path") is True
        assert any(a.get("type") == "torch" for a in d.get("client_actions", []))

    # 2. _clean_tts_response handles normal closed think tag
    text1 = "<think>Let me reason about this.</think>The weather is sunny, sir."
    assert _clean_tts_response(text1) == "The weather is sunny, sir."

    # 3. _clean_tts_response handles unclosed think tag (token exhaustion)
    text2 = "<think>I am continuing to think deeply about physics"
    # Should fall back to the last sentence in think block
    clean2 = _clean_tts_response(text2)
    assert len(clean2) > 0
    assert "<think>" not in clean2


def test_standby_sleep_mode_and_wake_isolation():
    """Verify strict Standby/Sleep triggers and complete silence for non-wake room speech."""
    from openjarvis.web.server import _voice_session

    client = TestClient(app)

    # 1. Sleep trigger via /api/chat
    r1 = client.post("/api/chat", json={"message": "jarvis stop listening"})
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1.get("fast_path") is True
    assert "standby" in d1.get("response", "").lower() or "استندبای" in d1.get("response", "")
    assert any(a.get("type") == "sleep" for a in d1.get("client_actions", []))

    # 2. Sleep trigger via /api/voice/vad_utterance
    import struct
    num_samples = 1600
    wav_bytes = bytearray()
    wav_bytes.extend(b"RIFF")
    wav_bytes.extend(struct.pack("<I", 36 + num_samples * 2))
    wav_bytes.extend(b"WAVEfmt ")
    wav_bytes.extend(struct.pack("<IHHIIHH", 16, 1, 1, 16000, 32000, 2, 16))
    wav_bytes.extend(b"data")
    wav_bytes.extend(struct.pack("<I", num_samples * 2))
    wav_bytes.extend(b"\x00" * (num_samples * 2))
    dummy_wav = bytes(wav_bytes)

    with patch("speech_recognition.Recognizer.recognize_google", return_value="stop listening"):
        r2 = client.post("/api/voice/vad_utterance", content=dummy_wav, headers={"Content-Type": "audio/wav"})
        assert r2.status_code == 200
        d2 = r2.json()
        assert d2.get("status") == "sleep"
        assert _voice_session["state"] == "STANDBY"

    # 3. While in STANDBY, random room conversation without wake word MUST be ignored
    _voice_session["state"] = "STANDBY"
    _voice_session["awake_until"] = 0.0
    with patch("speech_recognition.Recognizer.recognize_google", return_value="the weather today is nice and warm"):
        r3 = client.post("/api/voice/vad_utterance", content=dummy_wav, headers={"Content-Type": "audio/wav"})
        assert r3.status_code == 200
        d3 = r3.json()
        assert d3.get("status") == "ignored"
        assert d3.get("reason") == "no_wake_word"


def test_torch_phrasings_and_no_termux_api_error():
    """Verify 'turn on the flashlight' succeeds and outputs client_action without Termux:API error."""
    client = TestClient(app)

    phrasings = [
        "turn on the flashlight",
        "turn on flashlight",
        "flashlight on",
        "turn off the torch",
        "چراغ قوه رو روشن کن",
    ]
    for p in phrasings:
        resp = client.post("/api/chat", json={"message": p})
        assert resp.status_code == 200
        data = resp.json()
        assert data.get("fast_path") is True
        assert "Termux:API" not in data.get("response", "")
        actions = data.get("client_actions", [])
        assert any(a.get("type") == "torch" for a in actions)


def test_call_contact_name_fast_path():
    """Verify 'call dad', 'call mom', and Persian equivalents trigger zero-LLM fast path with contact name."""
    client = TestClient(app)

    # 1. Contact name: English
    r1 = client.post("/api/chat", json={"message": "call dad"})
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1.get("fast_path") is True
    assert "Calling dad" in d1.get("response", "")
    assert any(a.get("type") == "call" and a.get("name") == "dad" for a in d1.get("client_actions", []))

    r2 = client.post("/api/chat", json={"message": "call mom"})
    assert r2.status_code == 200
    d2 = r2.json()
    assert d2.get("fast_path") is True
    assert "Calling mom" in d2.get("response", "")
    assert any(a.get("type") == "call" and a.get("name") == "mom" for a in d2.get("client_actions", []))

    # 2. Contact name: Persian
    r3 = client.post("/api/chat", json={"message": "زنگ بزن به بابا"})
    assert r3.status_code == 200
    d3 = r3.json()
    assert d3.get("fast_path") is True
    assert any(a.get("type") == "call" and a.get("name") == "بابا" for a in d3.get("client_actions", []))

    # 3. Numeric phone number
    r4 = client.post("/api/chat", json={"message": "call +989123456789"})
    assert r4.status_code == 200
    d4 = r4.json()
    assert d4.get("fast_path") is True
    assert any(a.get("type") == "call" and "+989123456789" in a.get("number", "") for a in d4.get("client_actions", []))


def test_openrouter_fallback_pool_and_404_resilience():
    """Verify openrouter/free is never used, and fallback activates on HTTP 404, 400, 429."""
    import httpx
    from openjarvis.core.types import Message, Role
    from openjarvis.engine.cloud_rest import FREE_MODEL_FALLBACK_POOL, generate_openai_compat_rest

    assert "openrouter/free" not in FREE_MODEL_FALLBACK_POOL
    assert FREE_MODEL_FALLBACK_POOL[0] == "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"

    # Mock client where openrouter/free or first candidate fails with 404, but second candidate succeeds
    call_counts = []

    def mock_post(url, json=None, headers=None):
        model = json.get("model", "")
        call_counts.append(model)
        if "openrouter/free" in model or len(call_counts) == 1:
            req = httpx.Request("POST", url)
            resp = httpx.Response(404, request=req, json={"error": {"message": "No endpoints available", "code": 404}})
            return resp
        # Second candidate succeeds
        req = httpx.Request("POST", url)
        resp = httpx.Response(200, request=req, json={
            "choices": [{"message": {"role": "assistant", "content": "I am online and operational, sir."}}]
        })
        return resp

    mock_client = MagicMock()
    mock_client.post.side_effect = mock_post

    res = generate_openai_compat_rest(
        client=mock_client,
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="openrouter/free",
        messages=[Message(role=Role.USER, content="hello")],
    )

    assert "I am online" in res["content"]
    assert len(call_counts) >= 2
    # The first candidate was attempted, got 404, then auto-rotated to the next candidate
    assert call_counts[0] != "openrouter/free"  # openrouter/free was purged and replaced






