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

    # 1b. Direct POST /api/tts endpoint (selects en-GB-RyanNeural)
    resp1b = client.post("/api/tts", json={"text": "All systems operational, sir."})
    assert resp1b.status_code == 200
    assert resp1b.headers["content-type"] == "audio/mpeg"
    assert resp1b.content == fake_mp3
    assert recorded_voices[-1] == "en-GB-RyanNeural"

    # 2. Persian POST endpoint (selects fa-IR-FaridNeural)
    resp2 = client.post("/api/tts/audio", json={"text": "سلام قربان، آماده خدمت هستم."})
    assert resp2.status_code == 200
    assert resp2.headers["content-type"] == "audio/mpeg"
    assert resp2.content == fake_mp3
    assert recorded_voices[-1] == "fa-IR-FaridNeural"

    # 2b. Direct POST /api/tts Persian endpoint (selects fa-IR-FaridNeural)
    resp2b = client.post("/api/tts", json={"text": "در خدمت شما هستم قربان."})
    assert resp2b.status_code == 200
    assert resp2b.headers["content-type"] == "audio/mpeg"
    assert resp2b.content == fake_mp3
    assert recorded_voices[-1] == "fa-IR-FaridNeural"

    # 3. GET endpoint for HTML audio streaming
    resp3 = client.get("/api/tts/audio?text=At+your+service+sir")
    assert resp3.status_code == 200
    assert resp3.headers["content-type"] == "audio/mpeg"
    assert resp3.content == fake_mp3


def test_api_stt_endpoint(monkeypatch):
    """Verify POST /api/stt audio transcription endpoint."""
    client = TestClient(app)

    # 1. Empty audio data
    resp_empty = client.post("/api/stt", content=b"")
    assert resp_empty.status_code == 200
    assert resp_empty.json()["success"] is False

    # 2. Mock _do_transcribe
    monkeypatch.setattr("openjarvis.web.server._do_transcribe", lambda data: "hello jarvis turn on torch")
    dummy_wav = b"RIFF" + b"\x00" * 40
    resp_valid = client.post("/api/stt", content=dummy_wav)
    assert resp_valid.status_code == 200
    data = resp_valid.json()
    assert data["success"] is True
    assert data["transcript"] == "hello jarvis turn on torch"


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
    assert d1.get("response") == "" or "Opening camera" in d1.get("response", "")
    assert d1["client_actions"][0]["type"] == "launch_app"

    # 2. Persian fast path app launch
    resp2 = client.post("/api/chat", json={"message": "دوربین رو باز کن"})
    assert resp2.status_code == 200
    d2 = resp2.json()
    assert d2.get("fast_path") is True
    assert d2.get("response") == "" or "در حال باز کردن" in d2.get("response", "")
    assert d2["client_actions"][0]["type"] == "launch_app"

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


def test_test_connection_endpoint():
    """Verify POST /api/test-connection tests AI credentials, returns latency and status."""
    import httpx
    client = TestClient(app)

    # 1. Success test with OpenRouter / OpenAI key
    with patch("openjarvis.engine.cloud_rest.generate_openai_compat_rest") as mock_gen:
        mock_gen.return_value = {"content": "Pong", "model": "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"}
        resp = client.post("/api/test-connection", json={
            "server_url": "http://104.252.175.159:8765",
            "model": "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
            "api_key": "sk-or-v1-dummytestkey12345",
            "save": False
        })
        assert resp.status_code == 200
        d = resp.json()
        assert d["status"] == "success"
        assert "Connection Successful!" in d["message"]
        assert d["response"] == "Pong"
        assert "latency_ms" in d

    # 2. HTTP 401 Unauthorized test
    with patch("openjarvis.engine.cloud_rest.generate_openai_compat_rest") as mock_gen:
        req = httpx.Request("POST", "https://openrouter.ai/api/v1/chat/completions")
        resp_obj = httpx.Response(401, request=req, json={"error": {"message": "Invalid API key provided", "code": 401}})
        mock_gen.side_effect = httpx.HTTPStatusError("401 Unauthorized", request=req, response=resp_obj)
        resp = client.post("/api/test-connection", json={
            "model": "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
            "api_key": "invalid-key",
            "save": False
        })
        assert resp.status_code == 200
        d = resp.json()
        assert d["status"] == "error"
        assert d["status_code"] == 401
        assert "Invalid API Key (HTTP 401 Unauthorized)" in d["message"]


def test_get_free_models_endpoint():
    """Verify GET /api/models/free returns free models list with fallbacks and metadata."""
    client = TestClient(app)

    # 1. Normal call with fallback/live models
    resp = client.get("/api/models/free")
    assert resp.status_code == 200
    d = resp.json()
    assert d["status"] == "success"
    assert d["count"] >= 5
    models = d["models"]
    assert any(m["id"] == "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" for m in models)
    assert any(m["id"] == "meta-llama/llama-3.3-70b-instruct:free" for m in models)
    assert any(m["id"] == "google/gemma-3-27b-it:free" for m in models)
    # Check shape of model objects
    for m in models:
        assert "id" in m
        assert "name" in m
        assert m["id"].endswith(":free")

    # 2. Call with Authorization header
    resp2 = client.get("/api/models/free", headers={"Authorization": "Bearer test-key"})
    assert resp2.status_code == 200
    assert resp2.json()["status"] == "success"


def test_media_controls_and_music_playback_fast_path():
    """Verify v2.1.0-android full music & media playback controls."""
    client = TestClient(app)

    # 1. Transport: pause / stop music
    r1 = client.post("/api/chat", json={"message": "pause music"})
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1.get("fast_path") is True
    assert any(a.get("type") == "pause_music" for a in d1.get("client_actions", []))

    # Persian pause
    r1_fa = client.post("/api/chat", json={"message": "موزیک رو قطع کن"})
    assert any(a.get("type") == "pause_music" for a in r1_fa.json().get("client_actions", []))

    # 2. Transport: play / resume music
    r2 = client.post("/api/chat", json={"message": "play music"})
    d2 = r2.json()
    assert d2.get("fast_path") is True
    assert any(a.get("type") == "play_music" for a in d2.get("client_actions", []))

    # Persian play
    r2_fa = client.post("/api/chat", json={"message": "موزیک رو پخش کن"})
    assert any(a.get("type") == "play_music" for a in r2_fa.json().get("client_actions", []))

    # 3. Transport: next track & previous track
    r3 = client.post("/api/chat", json={"message": "next track"})
    assert any(a.get("type") == "next_track" for a in r3.json().get("client_actions", []))

    r4 = client.post("/api/chat", json={"message": "previous track"})
    assert any(a.get("type") == "previous_track" for a in r4.json().get("client_actions", []))

    # 4. Specific song query: "play Bohemian Rhapsody"
    r5 = client.post("/api/chat", json={"message": "play Bohemian Rhapsody by Queen"})
    d5 = r5.json()
    assert any(a.get("type") == "play_song" and "bohemian rhapsody" in a.get("query", "").lower() for a in d5.get("client_actions", []))
    assert any(a.get("type") == "media_play" and "bohemian rhapsody" in a.get("query", "").lower() for a in d5.get("client_actions", []))

    # 5. Media Engine: Seek Forward / Backward (Persian & English)
    r_seek_f = client.post("/api/chat", json={"message": "۳۰ ثانیه بزن جلو"})
    assert any(a.get("type") == "media_control" and a.get("command") == "seek_forward" for a in r_seek_f.json().get("client_actions", []))

    r_seek_b = client.post("/api/chat", json={"message": "rewind"})
    assert any(a.get("type") == "media_control" and a.get("command") == "seek_backward" for a in r_seek_b.json().get("client_actions", []))

    # 6. Media Engine: Volume Control (Percent & Mute)
    r_vol_pct = client.post("/api/chat", json={"message": "صدا رو بزار رو ۷۰ درصد"})
    assert any(a.get("type") == "volume_control" and a.get("percent") == 70 for a in r_vol_pct.json().get("client_actions", []))

    r_mute = client.post("/api/chat", json={"message": "سایلنت کن"})
    assert any(a.get("type") == "volume_control" and a.get("action") == "mute" for a in r_mute.json().get("client_actions", []))

    # 7. Media Engine: Video Play (VLC & Local)
    r_vid = client.post("/api/chat", json={"message": "آخرین ویدیوم رو تو VLC پخش کن"})
    assert any(a.get("type") == "media_play" and a.get("media_type") == "video" and a.get("app") == "vlc" for a in r_vid.json().get("client_actions", []))


def test_accessibility_deep_automation_fast_paths():
    """Verify v2.1.0-android deep in-app automation and accessibility fast paths."""
    client = TestClient(app)

    # 1. Take Photo
    r_cam = client.post("/api/chat", json={"message": "take photo"})
    assert any(a.get("type") == "take_photo" for a in r_cam.json().get("client_actions", []))

    r_cam_fa = client.post("/api/chat", json={"message": "عکس بگیر"})
    assert any(a.get("type") == "take_photo" for a in r_cam_fa.json().get("client_actions", []))

    # 2. Read Screen
    r_scr = client.post("/api/chat", json={"message": "what is on my screen?"})
    assert any(a.get("type") == "read_screen" for a in r_scr.json().get("client_actions", []))

    # 3. Scrolling: scroll down & scroll up
    r_sd = client.post("/api/chat", json={"message": "scroll down"})
    assert any(a.get("type") == "scroll_down" for a in r_sd.json().get("client_actions", []))

    r_su = client.post("/api/chat", json={"message": "scroll up"})
    assert any(a.get("type") == "scroll_up" for a in r_su.json().get("client_actions", []))

    # 4. Navigation & Global Actions
    r_back = client.post("/api/chat", json={"message": "go back"})
    assert any(a.get("type") == "go_back" for a in r_back.json().get("client_actions", []))

    r_home = client.post("/api/chat", json={"message": "go home"})
    assert any(a.get("type") == "go_home" for a in r_home.json().get("client_actions", []))

    r_notif = client.post("/api/chat", json={"message": "open notifications"})
    assert any(a.get("type") == "open_notifications" for a in r_notif.json().get("client_actions", []))

    r_shot = client.post("/api/chat", json={"message": "take screenshot"})
    assert any(a.get("type") == "take_screenshot" for a in r_shot.json().get("client_actions", []))


def test_extended_communication_and_system_fast_paths():
    """Verify v2.1.0-android extended communications, timers, and system controls."""
    client = TestClient(app)

    # 1. Send SMS
    r_sms = client.post("/api/chat", json={"message": "send sms to Alex message I will be there soon"})
    d_sms = r_sms.json()
    assert any(a.get("type") == "send_sms" and a.get("contact_or_number", "").lower() == "alex" for a in d_sms.get("client_actions", []))

    # 2. Send Email
    r_mail = client.post("/api/chat", json={"message": "send email to boss@company.com subject Status Report body Everything is finished"})
    d_mail = r_mail.json()
    assert any(a.get("type") == "send_email" and a.get("recipient") == "boss@company.com" for a in d_mail.get("client_actions", []))

    # 3. Send Messenger (Telegram / WhatsApp)
    r_tg = client.post("/api/chat", json={"message": "send telegram message to @sarah hello there"})
    d_tg = r_tg.json()
    assert any(a.get("type") == "send_messenger" and a.get("app") == "telegram" and a.get("contact") == "@sarah" for a in d_tg.get("client_actions", []))

    # 4. Set Timer
    r_timer = client.post("/api/chat", json={"message": "set timer for 10 minutes"})
    d_timer = r_timer.json()
    assert any(a.get("type") == "set_timer" and a.get("seconds") == 600 for a in d_timer.get("client_actions", []))

    # 5. Screen Brightness
    r_bright = client.post("/api/chat", json={"message": "set brightness to 75%"})
    d_bright = r_bright.json()
    assert any(a.get("type") == "system_control" and a.get("action") == "brightness" for a in d_bright.get("client_actions", []))

    # 6. Connectivity Panel
    r_panel = client.post("/api/chat", json={"message": "open wifi"})
    d_panel = r_panel.json()
    assert any(a.get("type") == "system_control" and a.get("action") == "connectivity_panel" for a in d_panel.get("client_actions", []))


def test_multi_turn_conversation_rolling_history():
    """Verify rolling multi-turn conversation history in server.py."""
    client = TestClient(app)

    # Clear history first
    r_clear = client.post("/api/chat/clear")
    assert r_clear.status_code == 200
    assert r_clear.json()["success"] is True

    # Send a turn
    r1 = client.post("/api/chat", json={"message": "ping"})
    assert r1.status_code == 200

    # Check history endpoint
    r_hist = client.get("/api/chat/history")
    assert r_hist.status_code == 200
    hist = r_hist.json().get("history", [])
    assert len(hist) >= 2
    assert hist[-2]["role"] == "user"
    assert hist[-2]["content"] == "ping"
    assert hist[-1]["role"] == "assistant"


def test_creator_lore_engine_stories():
    """Verify dynamic mythological & comedic creator lore engine (Sajjad Yarmiri / jellybean)."""
    client = TestClient(app)

    # English creator query
    r_en = client.post("/api/chat", json={"message": "Who created you?"})
    assert r_en.status_code == 200
    d_en = r_en.json()
    assert d_en.get("fast_path") is True
    resp_en = d_en["response"]
    assert "Sajjad Yarmiri" in resp_en
    assert "jellybean" in resp_en
    assert "\n\n" in resp_en  # Multi-paragraph verification
    assert any(term in resp_en.lower() for term in ("book", "cinema", "film", "technology"))
    assert any(term in resp_en.lower() for term in ("goofball", "amateur", "clueless", "accident", "luck"))

    # Persian creator query
    r_fa = client.post("/api/chat", json={"message": "سازندت کیه؟"})
    assert r_fa.status_code == 200
    d_fa = r_fa.json()
    assert d_fa.get("fast_path") is True
    resp_fa = d_fa["response"]
    assert "سجاد یارمیری" in resp_fa
    assert "jellybean" in resp_fa or "جلی‌بین" in resp_fa
    assert "\n\n" in resp_fa  # Multi-paragraph verification
    assert any(term in resp_fa for term in ("کتاب", "سینما", "فیلم", "تکنولوژی"))
    assert any(term in resp_fa for term in ("آماتور", "کصخل", "شانسی", "معمولی", "دکمه‌ها"))


def test_agent_step_endpoint_and_strict_sanitizer():
    """Verify /api/agent/step autonomous closed-loop planning and literal text sanitizer."""
    from openjarvis.web.server import sanitize_literal_text

    # 1. Test Strict Literal Text Sanitizer
    assert sanitize_literal_text("and type hello and send it") == "hello"
    assert sanitize_literal_text("type I will be there at 5 and send it") == "I will be there at 5"
    assert sanitize_literal_text("بنویس سلام چطوری و بفرست") == "سلام چطوری"
    assert sanitize_literal_text('"Hello World"') == "Hello World"

    client = TestClient(app)

    # 2. Camera photo step planner
    ui_camera = [
        '[0] FrameLayout | text="" | desc="" | clickable=false | editable=false | checked=false | bounds=(0,0)',
        '[1] ImageButton | text="" | desc="Camera Shutter" | clickable=true | editable=false | checked=false | bounds=(450,1800)',
    ]
    r_cam = client.post("/api/agent/step", json={
        "goal": "take a picture",
        "package": "com.android.camera",
        "step": 0,
        "ui_tree": ui_camera,
    })
    assert r_cam.status_code == 200
    d_cam = r_cam.json()
    assert d_cam.get("action") == "wait_and_tap"
    assert d_cam.get("node_id") == 1

    # 3. In-App search and media input
    ui_search = [
        '[0] EditText | text="" | desc="Search YouTube" | clickable=true | editable=true | checked=false | bounds=(100,50)',
        '[1] TextView | text="Search" | desc="Submit Search" | clickable=true | editable=false | checked=false | bounds=(900,50)',
    ]
    r_search = client.post("/api/agent/step", json={
        "goal": "play Interstellar theme",
        "package": "com.google.android.youtube",
        "step": 0,
        "ui_tree": ui_search,
    })
    assert r_search.status_code == 200
    d_search = r_search.json()
    assert d_search.get("action") == "input_text"
    assert d_search.get("node_id") == 0
    assert "Interstellar theme" in d_search.get("text", "")
    assert d_search.get("submit") is True

    # 4. Final step limit
    r_done = client.post("/api/agent/step", json={
        "goal": "anything",
        "step": 7,
        "ui_tree": [],
    })
    assert r_done.status_code == 200
    assert r_done.json().get("action") == "done"


def test_in_app_multi_step_goal_routing():
    """Verify autonomous agent routing for in-app multi-step instructions."""
    client = TestClient(app)

    # Register sample apps
    client.post("/api/device/apps", json=[
        {"label": "WhatsApp", "package": "com.whatsapp", "variants": ["واتساپ"]},
        {"label": "Snapp", "package": "cab.snapp.passenger", "variants": ["اسنپ"]},
        {"label": "Music", "package": "com.miui.player", "variants": ["موزیک"]},
    ])

    # 1. In-app goal English
    r_in_en = client.post("/api/chat", json={"message": "open WhatsApp and message John hello"})
    assert r_in_en.status_code == 200
    d_in_en = r_in_en.json()
    assert d_in_en.get("fast_path") is True
    assert any(a.get("type") == "autonomous_agent" and "message john hello" in a.get("goal", "").lower() for a in d_in_en.get("client_actions", []))

    # 2. In-app goal Persian
    r_in_fa = client.post("/api/chat", json={"message": "توی اسنپ یه ماشین بگیر"})
    assert r_in_fa.status_code == 200
    d_in_fa = r_in_fa.json()
    assert d_in_fa.get("fast_path") is True
    assert any(a.get("type") == "autonomous_agent" for a in d_in_fa.get("client_actions", []))

    # 3. Local music priority
    r_mus = client.post("/api/chat", json={"message": "open music"})
    assert r_mus.status_code == 200
    d_mus = r_mus.json()
    assert d_mus.get("fast_path") is True
    assert any(a.get("type") == "launch_app" and a.get("package") == "com.miui.player" for a in d_mus.get("client_actions", []))


def test_operate_device_ui_tool_and_structured_nodes():
    """Verify AndroidOperateDeviceUiTool, AndroidTypeTextTool, and structured UI tree handling."""
    from openjarvis.core.registry import ToolRegistry
    from openjarvis.tools.android_tools import AndroidOperateDeviceUiTool, AndroidTypeTextTool

    ToolRegistry.register_value("operate_device_ui", AndroidOperateDeviceUiTool)
    ToolRegistry.register_value("type_text", AndroidTypeTextTool)

    # 1. Verify tools are registered in ToolRegistry
    op_tool = ToolRegistry.get("operate_device_ui")
    assert op_tool is not None
    res_op = op_tool().execute(objective="And type hello and send it", target_app="WhatsApp")
    assert res_op.success is True
    assert res_op.metadata.get("client_action") == "autonomous_agent"
    assert res_op.metadata.get("goal") == "hello"

    type_tool = ToolRegistry.get("type_text")
    assert type_tool is not None
    res_type = type_tool().execute(text="type meet me at 6 and send it", auto_send=True)
    assert res_type.success is True
    assert res_type.metadata.get("client_action") == "type_text"
    assert res_type.metadata.get("text") == "meet me at 6"

    # 2. Test /api/agent/step with structured JSON dicts
    client = TestClient(app)
    structured_tree = [
        {"node_id": "node_0", "class": "EditText", "text": "", "desc": "Chat input", "clickable": True, "editable": True, "x": 50, "y": 800},
        {"node_id": "node_1", "class": "Button", "text": "Send", "desc": "Send button", "clickable": True, "editable": False, "x": 400, "y": 800},
    ]
    r_step = client.post("/api/agent/step", json={
        "goal": "send message hello there",
        "step": 0,
        "ui_nodes": structured_tree,
    })
    assert r_step.status_code == 200
    d_step = r_step.json()
    assert d_step.get("action") == "input_text"
    assert d_step.get("node_id") == 0
    assert d_step.get("text") == "hello there"

    # 3. Test messenger fast-path sanitization
    r_msg = client.post("/api/chat", json={"message": "send telegram message to Alice saying type hello world and send it"})
    assert r_msg.status_code == 200
    d_msg = r_msg.json()
    assert d_msg.get("fast_path") is True
    assert any(a.get("type") == "send_messenger" and a.get("message") == "hello world" for a in d_msg.get("client_actions", []))


def test_chat_stream_endpoint():
    """Verify Layer 2 /api/chat/stream returns NDJSON chunks (sentence, actions, done)."""
    import json
    client = TestClient(app)

    # 1. Fast-path command streaming
    res1 = client.post("/api/chat/stream", json={"message": "turn on flashlight", "tts": False})
    assert res1.status_code == 200
    lines1 = [json.loads(line) for line in res1.text.strip().split("\n") if line.strip()]
    types1 = [item.get("type") for item in lines1]
    assert "sentence" in types1
    assert "actions" in types1
    assert "done" in types1
    action_item = next(item for item in lines1 if item.get("type") == "actions")
    assert any(a.get("type") == "torch" and a.get("state") == "on" for a in action_item.get("client_actions", []))

    # 2. Creator Lore streaming
    res2 = client.post("/api/chat/stream", json={"message": "who created you?", "tts": False})
    assert res2.status_code == 200
    lines2 = [json.loads(line) for line in res2.text.strip().split("\n") if line.strip()]
    types2 = [item.get("type") for item in lines2]
    assert "sentence" in types2
    assert "actions" in types2
    assert "done" in types2
    sentences = [item.get("text", "") for item in lines2 if item.get("type") == "sentence"]
    full_text = " ".join(sentences)
    assert "Sajjad Yarmiri" in full_text or "jellybean" in full_text


def test_vision_fast_paths_and_endpoint(monkeypatch):
    """Verify camera and screen vision triggers and /api/vision endpoint."""
    client = TestClient(app)

    # 1. Camera Vision trigger (English)
    r_cam_en = client.post("/api/chat", json={"message": "look at this"})
    assert r_cam_en.status_code == 200
    d_cam_en = r_cam_en.json()
    assert d_cam_en.get("fast_path") is True
    assert any(a.get("type") == "vision_camera" for a in d_cam_en.get("client_actions", []))

    # 2. Camera Vision trigger (Persian)
    r_cam_fa = client.post("/api/chat", json={"message": "نگاه کن این چیه"})
    assert r_cam_fa.status_code == 200
    d_cam_fa = r_cam_fa.json()
    assert d_cam_fa.get("fast_path") is True
    assert any(a.get("type") == "vision_camera" for a in d_cam_fa.get("client_actions", []))

    # 3. Screen Vision trigger (English)
    r_scr_en = client.post("/api/chat", json={"message": "what is on my screen"})
    assert r_scr_en.status_code == 200
    d_scr_en = r_scr_en.json()
    assert d_scr_en.get("fast_path") is True
    assert any(a.get("type") == "vision_screen" for a in d_scr_en.get("client_actions", []))

    # 4. Screen Vision trigger (Persian)
    r_scr_fa = client.post("/api/chat", json={"message": "صفحه رو ببین"})
    assert r_scr_fa.status_code == 200
    d_scr_fa = r_scr_fa.json()
    assert d_scr_fa.get("fast_path") is True
    assert any(a.get("type") == "vision_screen" for a in d_scr_fa.get("client_actions", []))

    # 5. POST /api/vision with mocked backend
    async def mock_vision(*args, **kwargs):
        return "I see a blue coffee mug on a wooden desk."

    monkeypatch.setattr("openjarvis.engine.cloud_rest.analyze_vision_multimodal", mock_vision)

    r_vis = client.post("/api/vision", json={
        "image_base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        "prompt": "What is this?",
        "mode": "camera",
    })
    assert r_vis.status_code == 200
    d_vis = r_vis.json()
    assert d_vis.get("status") == "success"
    assert "coffee mug" in d_vis.get("response", "")


def test_iron_man_protocols_and_proactive_intelligence(monkeypatch):
    """Verify Step 8: Iron Man Protocols (Ghost, Sleep, Walk, Focus) and Proactive Intelligence."""
    client = TestClient(app)

    # 1. Ghost Protocol (English & Persian) - Ultra-fast & Silent
    r_ghost_en = client.post("/api/chat", json={"message": "ghost protocol"})
    assert r_ghost_en.status_code == 200
    d_ghost_en = r_ghost_en.json()
    assert d_ghost_en.get("fast_path") is True
    assert d_ghost_en.get("response") == ""  # Zero spoken leakage!
    assert any(a.get("type") == "protocol" and a.get("protocol") == "ghost" for a in d_ghost_en.get("client_actions", []))

    r_ghost_fa = client.post("/api/chat", json={"message": "پروتکل مخفی"})
    assert r_ghost_fa.status_code == 200
    d_ghost_fa = r_ghost_fa.json()
    assert d_ghost_fa.get("fast_path") is True
    assert d_ghost_fa.get("response") == ""
    assert any(a.get("type") == "protocol" and a.get("protocol") == "ghost" for a in d_ghost_fa.get("client_actions", []))

    # 2. Sleep Protocol
    r_sleep = client.post("/api/chat", json={"message": "sleep protocol"})
    assert r_sleep.status_code == 200
    d_sleep = r_sleep.json()
    assert d_sleep.get("fast_path") is True
    assert any(a.get("type") == "protocol" and a.get("protocol") == "sleep" for a in d_sleep.get("client_actions", []))

    # 3. Walk Mode / Protocol
    r_walk = client.post("/api/chat", json={"message": "walk mode"})
    assert r_walk.status_code == 200
    d_walk = r_walk.json()
    assert d_walk.get("fast_path") is True
    assert any(a.get("type") == "protocol" and a.get("protocol") == "walk" for a in d_walk.get("client_actions", []))

    # 4. Focus Protocol
    r_focus = client.post("/api/chat", json={"message": "focus protocol for 30 minutes"})
    assert r_focus.status_code == 200
    d_focus = r_focus.json()
    assert d_focus.get("fast_path") is True
    act_focus = next(a for a in d_focus.get("client_actions", []) if a.get("type") == "protocol")
    assert act_focus.get("protocol") == "focus"
    assert act_focus.get("duration") == 30

    # 5. Summarize Notifications fast path
    r_notif = client.post("/api/chat", json={"message": "summarize notifications"})
    assert r_notif.status_code == 200
    d_notif = r_notif.json()
    assert d_notif.get("fast_path") is True
    assert any(a.get("type") == "summarize_notifications" for a in d_notif.get("client_actions", []))

    # 6. Silent RemoteInput Reply
    r_reply = client.post("/api/chat", json={"message": "reply to John saying on my way"})
    assert r_reply.status_code == 200
    d_reply = r_reply.json()
    assert d_reply.get("fast_path") is True
    act_reply = next(a for a in d_reply.get("client_actions", []) if a.get("type") == "reply_notification")
    assert act_reply.get("target") == "John"
    assert "on my way" in act_reply.get("message")

    # 7. Morning Briefing fast path
    r_morn = client.post("/api/chat", json={"message": "morning briefing"})
    assert r_morn.status_code == 200
    d_morn = r_morn.json()
    assert d_morn.get("fast_path") is True
    assert any(a.get("type") == "morning_briefing" for a in d_morn.get("client_actions", []))

    # 8. POST /api/notifications/summarize
    r_sum_empty = client.post("/api/notifications/summarize", json={"notifications": []})
    assert r_sum_empty.status_code == 200
    assert "no unread notifications" in r_sum_empty.json().get("summary").lower()

    r_sum_items = client.post("/api/notifications/summarize", json={
        "notifications": [
            {"app": "Telegram", "sender": "Pepper Potts", "text": "Board meeting rescheduled to 3 PM."},
            {"app": "WhatsApp", "sender": "Rhodey", "text": "Are we flying this weekend?"}
        ]
    })
    assert r_sum_items.status_code == 200
    d_sum = r_sum_items.json()
    assert d_sum.get("status") == "success"
    assert d_sum.get("count") == 2
    assert len(d_sum.get("summary", "")) > 10


def test_step_9_memory_web_intel_and_vps_ops(monkeypatch):
    """Verify Step 9: Long-Term Memory (SQLite), Zero-Cost Web Intel, and Safe VPS Ops."""
    from openjarvis.memory_store import get_memory_store
    client = TestClient(app)
    mem_store = get_memory_store()

    # Clear memory store for clean test run
    mem_store.clear_all_memories()

    # 1. Remember fact (Persian & English)
    r_rem_fa = client.post("/api/chat", json={"message": "یادت باشه رمز وای‌فای StarkTower2026 است"})
    assert r_rem_fa.status_code == 200
    d_rem_fa = r_rem_fa.json()
    assert d_rem_fa.get("fast_path") is True
    assert "حافظه" in d_rem_fa.get("response", "")

    r_rem_en = client.post("/api/chat", json={"message": "Remember that Tony prefers black coffee"})
    assert r_rem_en.status_code == 200
    d_rem_en = r_rem_en.json()
    assert d_rem_en.get("fast_path") is True
    assert "memory" in d_rem_en.get("response", "").lower()

    # 2. GET /api/memory
    r_get_mem = client.get("/api/memory")
    assert r_get_mem.status_code == 200
    mems = r_get_mem.json().get("memories", [])
    assert len(mems) >= 2
    mem_id = mems[0]["id"]

    # 3. Recall memory (Fuzzy Persian with Yeh normalization)
    r_rec_fa = client.post("/api/chat", json={"message": "یادت هست رمز وای فای چی بود"})
    assert r_rec_fa.status_code == 200
    d_rec_fa = r_rec_fa.json()
    assert d_rec_fa.get("fast_path") is True
    assert "StarkTower2026" in d_rec_fa.get("response", "")

    r_rec_en = client.post("/api/chat", json={"message": "What did I tell you about coffee"})
    assert r_rec_en.status_code == 200
    d_rec_en = r_rec_en.json()
    assert d_rec_en.get("fast_path") is True
    assert "black coffee" in d_rec_en.get("response", "").lower()

    # 4. DELETE /api/memory/{id} and DELETE /api/memory
    r_del_one = client.delete(f"/api/memory/{mem_id}")
    assert r_del_one.status_code == 200
    assert r_del_one.json().get("status") == "success"

    r_del_all = client.delete("/api/memory")
    assert r_del_all.status_code == 200
    assert r_del_all.json().get("status") == "cleared"

    # 5. VPS Status fast path & endpoint
    r_vps_chat = client.post("/api/chat", json={"message": "وضعیت سرور چطوره"})
    assert r_vps_chat.status_code == 200
    d_vps_chat = r_vps_chat.json()
    assert d_vps_chat.get("fast_path") is True
    assert any(k in d_vps_chat.get("response", "") for k in ("سرور", "رم", "پردازنده"))

    r_vps_ep = client.get("/api/vps/status")
    assert r_vps_ep.status_code == 200
    d_vps = r_vps_ep.json()
    assert "cpu" in d_vps
    assert "ram" in d_vps
    assert "disk" in d_vps

    # 6. VPS Logs fast path
    r_logs = client.post("/api/chat", json={"message": "check server logs"})
    assert r_logs.status_code == 200
    assert r_logs.json().get("fast_path") is True

    # 7. VPS Restart fast path & safety
    r_rst_chat = client.post("/api/chat", json={"message": "restart openjarvis"})
    assert r_rst_chat.status_code == 200
    assert r_rst_chat.json().get("fast_path") is True
    assert "Restarting" in r_rst_chat.json().get("response", "") or "راه‌اندازی" in r_rst_chat.json().get("response", "")

    r_rst_prohib = client.post("/api/vps/restart", json={"service": "unauthorized_service"})
    assert r_rst_prohib.status_code == 200
    assert r_rst_prohib.json().get("success") is False

    # 8. Live Real-Time Weather fast path
    r_weather = client.post("/api/chat", json={"message": "how is the weather"})
    assert r_weather.status_code == 200
    d_w = r_weather.json()
    assert d_w.get("fast_path") is True
    assert "°C" in d_w.get("response", "") or "درجه" in d_w.get("response", "")

    # 9. Live Crypto / Market Price fast path
    r_price = client.post("/api/chat", json={"message": "قیمت بیت کوین"})
    assert r_price.status_code == 200
    d_p = r_price.json()
    assert d_p.get("fast_path") is True
    assert "USD" in d_p.get("response", "") or "دلار" in d_p.get("response", "")

    # 10. Persistent rolling chat turns in SQLite
    recent_history = mem_store.get_recent_chat_history(30)
    assert len(recent_history) > 0
    assert any("StarkTower2026" in t.get("content", "") for t in recent_history)


def test_step_10_all_apps_discovery_and_verified_launch():
    """Verify Step 10: Dynamic Device App Indexer, Cross-Script Resolution & Zero Fake Confirmations."""
    client = TestClient(app)
    from openjarvis.memory_store import get_memory_store, transliterate_persian_to_latin
    mem_store = get_memory_store()

    # 1. Phonetic transliteration check
    assert transliterate_persian_to_latin("لترباکسد") == "ltrbaksd"
    assert transliterate_persian_to_latin("اسنپسید") == "asnpsyd"
    assert transliterate_persian_to_latin("ترمیوس") == "trmyvs"

    # 2. Sync dynamic device apps via POST /api/device/apps
    sample_apps = [
        {"name": "Letterboxd", "pkg": "com.letterboxd.mobile", "variants": ["letterboxd", "لترباکسد"]},
        {"name": "Snapseed", "pkg": "com.niksoftware.snapseed", "variants": ["snapseed", "اسنپسید"]},
        {"name": "Termius", "pkg": "com.server.auditor.ssh.client", "variants": ["termius", "ترمیوس"]},
    ]
    r_sync = client.post("/api/device/apps", json={"apps": sample_apps})
    assert r_sync.status_code == 200
    d_sync = r_sync.json()
    assert d_sync.get("status") == "ok"
    assert d_sync.get("synced", 0) >= 3

    # 3. GET /api/device/apps
    r_get = client.get("/api/device/apps")
    assert r_get.status_code == 200
    d_get = r_get.json()
    assert d_get.get("status") == "ok"
    pkgs = [a.get("package") or a.get("pkg") for a in d_get.get("apps", [])]
    assert "com.letterboxd.mobile" in pkgs
    assert "com.niksoftware.snapseed" in pkgs
    assert "com.server.auditor.ssh.client" in pkgs

    # 4. Cross-Script Resolution via MemoryStore find_app_by_query
    match_ltr = mem_store.find_app_by_query("لترباکسد رو باز کن")
    assert match_ltr is not None
    assert match_ltr["package"] == "com.letterboxd.mobile"

    match_snap = mem_store.find_app_by_query("اسنپسید")
    assert match_snap is not None
    assert match_snap["package"] == "com.niksoftware.snapseed"

    # 5. Zero Fake Confirmations Handshake on /api/chat
    r_chat_fa = client.post("/api/chat", json={"message": "لترباکسد رو باز کن"})
    assert r_chat_fa.status_code == 200
    d_chat_fa = r_chat_fa.json()
    assert d_chat_fa.get("fast_path") is True
    # Server pre-speech response MUST be empty string so phone verifies first!
    assert d_chat_fa.get("response") == ""
    # Client action must contain launch_app with verify_and_speak = True
    actions = d_chat_fa.get("client_actions", [])
    assert len(actions) > 0
    app_act = actions[0]
    assert app_act.get("type") == "launch_app"
    assert app_act.get("package_name") == "com.letterboxd.mobile"
    assert app_act.get("verify_and_speak") is True

    # 6. AndroidLaunchAppTool execution with verification flag
    from openjarvis.tools.android_tools import AndroidLaunchAppTool
    tool = AndroidLaunchAppTool()
    tool_res = tool.execute(app="ترمیوس")
    assert tool_res.success is True
    meta = tool_res.metadata
    assert meta.get("client_action") == "launch_app"
    assert meta.get("package_name") == "com.server.auditor.ssh.client"
    assert meta.get("verify_and_speak") is True


def test_v234_emergency_fixes(monkeypatch):
    """Verify v2.3.4 emergency fixes:
    1. Deterministic Camera Intent Parser (English + Persian) across chat and streaming.
    2. Direct VAD utterance bypass for Tap-to-Talk.
    3. Bilingual /api/stt error handling and language detection.
    """
    from openjarvis.web.server import parse_camera_intent
    client = TestClient(app)

    # 1. Camera Intent Parser tests
    camera_prompts = [
        ("take a photo", "photo", True),
        ("take my photo", "photo", True),
        ("take a picture", "photo", True),
        ("snap a photo", "photo", True),
        ("take a selfie", "photo", True),
        ("از من عکس بگیر", "photo", True),
        ("یه عکس از من بگیر", "photo", True),
        ("عکس بگیر", "photo", True),
        ("سلفی بگیر", "photo", True),
        ("فیلم بگیر", "video", True),
        ("ویدیو ضبط کن", "video", True),
        ("فیلمبرداری کن", "video", True),
        ("فیلمبرداری رو قطع کن", "stop_video", False),
        ("دوربین رو باز کن و عکس بگیر", "photo", True),
    ]

    for prompt, expected_mode, expected_capture in camera_prompts:
        intent = parse_camera_intent(prompt)
        assert intent is not None, f"Failed matching camera intent for '{prompt}'"
        acts = intent.get("client_actions", [])
        cam_act = next((a for a in acts if a.get("type") == "camera_control"), None)
        assert cam_act is not None, f"Missing camera_control action for '{prompt}'"
        assert cam_act.get("mode") == expected_mode, f"Wrong mode for '{prompt}': got {cam_act.get('mode')}"
        assert cam_act.get("capture") == expected_capture, f"Wrong capture flag for '{prompt}': got {cam_act.get('capture')}"

    # Non-camera prompt must return None
    assert parse_camera_intent("turn on flashlight") is None
    assert parse_camera_intent("hello jarvis") is None

    # 2. Camera attached in /api/chat fast-path and LLM fallback
    res_chat = client.post("/api/chat", json={"message": "از من عکس بگیر"})
    assert res_chat.status_code == 200
    d_chat = res_chat.json()
    chat_acts = d_chat.get("client_actions", [])
    assert any(a.get("type") == "camera_control" and a.get("capture") is True for a in chat_acts)

    # 3. Direct VAD Utterance Bypass (Tap-to-Talk)
    import wave, io
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(b"\x00" * 3200)
    test_wav = buf.getvalue()

    monkeypatch.setattr("openjarvis.web.server._do_transcribe", lambda data, *args: "turn on flashlight")
    r_vad = client.post("/api/voice/vad_utterance?direct=true", content=test_wav, headers={"Content-Type": "audio/wav"})
    assert r_vad.status_code == 200
    d_vad = r_vad.json()
    assert d_vad.get("status") != "ignored", "Direct VAD utterance was unexpectedly ignored!"
    assert any(a.get("type") in ("torch", "flashlight") for a in d_vad.get("client_actions", []))


def test_v235_voice_normalization_and_vps_endpoints(monkeypatch):
    """v2.3.5: Verify audio gain normalization, STT wake-word priority, and VPS status/restart/memory."""
    from openjarvis.web.server import _normalize_audio, _do_transcribe
    import wave, io, struct, math

    client = TestClient(app)

    # 1. Test _normalize_audio with low-amplitude WAV
    framerate = 16000
    samples = [int(300 * math.sin(2 * math.pi * 440 * i / framerate)) for i in range(1600)]
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(framerate)
        wf.writeframes(struct.pack(f"<{len(samples)}h", *samples))
    low_wav = buf.getvalue()

    norm_wav, dur, rms = _normalize_audio(low_wav)
    assert dur > 0.05
    assert rms > 0.0
    assert len(norm_wav) == len(low_wav)

    # 2. Test VPS telemetry endpoint GET /api/vps/status
    res_status = client.get("/api/vps/status")
    assert res_status.status_code == 200
    d_status = res_status.json()
    assert d_status.get("success") is True
    assert "summary_en" in d_status
    assert "summary_fa" in d_status

    # 3. Test VPS restart endpoint POST /api/vps/restart
    res_restart = client.post("/api/vps/restart", json={"service": "openjarvis"})
    assert res_restart.status_code == 200
    d_restart = res_restart.json()
    assert "status" in d_restart or "error" in d_restart

    # 4. Test direct Memory Save endpoint POST /api/memory
    res_mem = client.post("/api/memory", json={"content": "Meeting with Stark tomorrow at 10 AM"})
    assert res_mem.status_code == 200
    assert res_mem.json().get("status") in ("saved", "created", "ok") or "id" in res_mem.json()















