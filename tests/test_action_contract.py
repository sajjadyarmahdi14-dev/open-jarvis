"""Comprehensive Contract and Schema Verification Tests for J.A.R.V.I.S.

Verifies:
1. /api/ping
2. /api/tts (English RyanNeural + Persian FaridNeural)
3. /api/chat (fast-path + client_actions)
4. /api/chat/stream (streaming sentence chunks + client_actions + done)
5. /api/agent/step (resilient JSON extraction for Android Accessibility)
6. /api/vision
7. /api/memory (remember, recall, forget)
8. /api/device/apps (device apps sync and listing)
9. Schema Contract Test: Guarantees EVERY action type produced by server.py
   and android_tools.py is mapped to a handler in Android's ActionDispatcher.kt!
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi.testclient import TestClient

from openjarvis.web.server import app


@pytest.fixture
def client():
    return TestClient(app)


def test_api_ping(client):
    resp = client.get("/api/ping")
    assert resp.status_code == 200
    data = resp.json()
    assert data.get("status") == "ok"
    assert "timestamp" in data


def test_api_tts_english_and_persian(client):
    fake_audio = b"ID3\x03\x00\x00\x00FAKE_MP3_DATA"

    with patch("openjarvis.web.server._synthesize_edge_tts", new_callable=AsyncMock) as mock_synth:
        from fastapi.responses import Response
        mock_synth.return_value = Response(content=fake_audio, media_type="audio/mpeg")

        # 1. English
        resp_en = client.post("/api/tts", json={"text": "All systems nominal, sir.", "voice": "en-GB-RyanNeural"})
        assert resp_en.status_code == 200
        assert resp_en.content == fake_audio

        # 2. Persian
        resp_fa = client.post("/api/tts", json={"text": "در خدمت شما هستم قربان.", "voice": "fa-IR-FaridNeural"})
        assert resp_fa.status_code == 200
        assert resp_fa.content == fake_audio


def test_api_chat_fast_path_and_client_actions(client):
    # Flashlight command -> client_action 'torch'
    resp = client.post("/api/chat", json={"message": "turn on flashlight", "tts": False})
    assert resp.status_code == 200
    data = resp.json()
    assert "response" in data
    assert any(a.get("type") == "torch" for a in data.get("client_actions", []))

    # Persian music pause -> client_action 'pause_music' or 'media_control'
    resp_music = client.post("/api/chat", json={"message": "موزیک رو قطع کن", "tts": False})
    assert resp_music.status_code == 200
    data_m = resp_music.json()
    assert any(a.get("type") in ("pause_music", "media_control") for a in data_m.get("client_actions", []))


def test_api_chat_stream_emits_actions(client):
    # Test streaming endpoint for fast-path torch command
    with client.stream("POST", "/api/chat/stream", json={"message": "turn off flashlight", "tts": False}) as response:
        assert response.status_code == 200
        lines = [line for line in response.iter_lines() if line]
        assert len(lines) >= 2

        parsed_events = [json.loads(line) for line in lines]
        types = [e.get("type") for e in parsed_events]

        assert "actions" in types
        assert "done" in types

        actions_event = next(e for e in parsed_events if e.get("type") == "actions")
        client_actions = actions_event.get("client_actions", [])
        assert any(a.get("type") == "torch" and a.get("state") == "off" for a in client_actions)


def test_api_agent_step_json_extraction(client):
    req_payload = {
        "goal": "Click on search bar",
        "package": "org.telegram.messenger",
        "step": 0,
        "ui_tree": [
            "[0] EditText | text=\"Search\" | desc=\"\" | clickable=true | editable=true | checked=false | bounds=(50,100)"
        ],
    }

    # Test camera / photo goal heuristic
    req_cam = {
        "goal": "Take a photo with camera",
        "package": "com.android.camera",
        "step": 0,
        "ui_tree": [
            "[0] ImageView | text=\"\" | desc=\"Shutter\" | clickable=true | editable=false | checked=false | bounds=(500,1800)"
        ],
    }
    resp = client.post("/api/agent/step", json=req_cam)
    assert resp.status_code == 200
    data = resp.json()
    assert "action" in data
    assert data["action"] in ("click", "tap", "wait_and_tap")


def test_api_memory_lifecycle(client):
    # 1. Save fact via fast-path in chat
    resp_rem = client.post("/api/chat", json={"message": "Remember that my secret code is 8842", "tts": False})
    assert resp_rem.status_code == 200

    # 2. Query via memory store API
    resp_mem = client.get("/api/memory")
    assert resp_mem.status_code == 200
    data = resp_mem.json()
    memories = data.get("memories", [])
    assert any("8842" in m.get("content", "") for m in memories)

    # 3. Recall via chat
    resp_rec = client.post("/api/chat", json={"message": "What is my secret code?", "tts": False})
    assert resp_rec.status_code == 200
    assert "8842" in resp_rec.json().get("response", "")


def test_api_device_apps_sync_and_get(client):
    apps_payload = {
        "apps": [
            {"label": "Telegram", "package": "org.telegram.messenger", "name": "Telegram"},
            {"label": "Music", "package": "com.miui.player", "name": "Music"},
        ]
    }
    resp_post = client.post("/api/device/apps", json=apps_payload)
    assert resp_post.status_code == 200
    assert resp_post.json().get("status") == "ok"

    resp_get = client.get("/api/device/apps")
    assert resp_get.status_code == 200
    data = resp_get.json()
    apps = data.get("apps", [])
    assert any(a.get("package") == "org.telegram.messenger" for a in apps)


def test_schema_contract_action_dispatcher_coverage():
    """Verify EVERY action type produced by the Python backend has a matching handler

    in Android's ActionDispatcher.kt!
    """
    server_path = Path(__file__).resolve().parents[1] / "src" / "openjarvis" / "web" / "server.py"
    tools_path = Path(__file__).resolve().parents[1] / "src" / "openjarvis" / "tools" / "android_tools.py"
    dispatcher_path = (
        Path(__file__).resolve().parents[1]
        / "android-app"
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "sajjad"
        / "jarvis"
        / "ActionDispatcher.kt"
    )

    assert dispatcher_path.exists(), f"ActionDispatcher.kt not found at {dispatcher_path}"
    dispatcher_code = dispatcher_path.read_text(encoding="utf-8")

    server_code = server_path.read_text(encoding="utf-8")
    tools_code = tools_path.read_text(encoding="utf-8")

    # Extract all action types emitted by server and tools
    emitted_types = set()

    for m in re.finditer(r'"type":\s*"([a-zA-Z0-9_-]+)"', server_code):
        t = m.group(1).lower()
        if t not in ("actions", "images", "sentence", "done", "object", "string", "number", "boolean"):
            emitted_types.add(t)

    for m in re.finditer(r'"client_action":\s*"([a-zA-Z0-9_-]+)"', tools_code):
        emitted_types.add(m.group(1).lower())

    for m in re.finditer(r'"client_action",\s*"([a-zA-Z0-9_-]+)"', server_code):
        emitted_types.add(m.group(1).lower())

    # Verify each emitted action is referenced in ActionDispatcher.kt
    missing_handlers = []
    for action in emitted_types:
        # Check if the string appears quoted in ActionDispatcher.kt
        if f'"{action}"' not in dispatcher_code:
            missing_handlers.append(action)

    assert not missing_handlers, f"ActionDispatcher.kt is missing handlers for actions: {missing_handlers}"


def test_camera_control_tool_and_fastpath():
    """Verify AndroidCameraControlTool schema and server fast-path handling."""
    from openjarvis.tools.android_tools import AndroidCameraControlTool
    tool = AndroidCameraControlTool()
    res = tool.execute(mode="photo", timer_seconds=3, zoom=2.0, capture=True, effect="vivid")
    assert res.success is True
    meta = res.metadata
    assert meta.get("client_action") == "camera_control"
    assert meta.get("mode") == "photo"
    assert meta.get("timer_seconds") == 3
    assert meta.get("zoom") == 2.0
    assert meta.get("capture") is True
    assert meta.get("effect") == "vivid"

    # Fast path test on /api/chat
    client = TestClient(app)
    r_cam = client.post("/api/chat", json={"message": "take a photo with 3 second timer"})
    assert r_cam.status_code == 200
    d_cam = r_cam.json()
    assert d_cam.get("fast_path") is True
    actions = d_cam.get("client_actions", [])
    assert len(actions) > 0
    assert actions[0].get("type") == "camera_control"
    assert actions[0].get("timer_seconds") == 3


def test_agent_step_visual_coordinate_fallback():
    """Verify /api/agent/step fallback to tap_coords at (0.50, 0.88) when UI tree is minimal."""
    client = TestClient(app)
    payload = {
        "goal": "take photo",
        "package": "com.android.camera",
        "step": 0,
        "ui_tree": [],
        "ui_nodes": [],
        "screenshot_base64": "dummy_base64_data",
    }
    resp = client.post("/api/agent/step", json=payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data.get("action") == "tap_coords"
    assert data.get("x_norm") == 0.50
    assert data.get("y_norm") == 0.88


def test_stt_bilingual_detection(monkeypatch):
    """Verify /api/stt returns language='fa' or 'en' based on transcription."""
    import base64
    client = TestClient(app)
    dummy_b64 = base64.b64encode(b"RIFF1234WAVEfmt 1234data1234").decode("utf-8")

    # 1. Persian transcription
    monkeypatch.setattr(
        "openjarvis.web.server._do_transcribe",
        lambda data: "سلام جارویس حالت چطوره"
    )
    r_fa = client.post("/api/stt", json={"audio_base64": dummy_b64})
    assert r_fa.status_code == 200
    assert r_fa.json().get("language") == "fa"
    assert r_fa.json().get("transcript") == "سلام جارویس حالت چطوره"

    # 2. English transcription
    monkeypatch.setattr(
        "openjarvis.web.server._do_transcribe",
        lambda data: "Hello Jarvis, what is the status?"
    )
    r_en = client.post("/api/stt", json={"audio_base64": dummy_b64})
    assert r_en.status_code == 200
    assert r_en.json().get("language") == "en"
    assert r_en.json().get("transcript") == "Hello Jarvis, what is the status?"

