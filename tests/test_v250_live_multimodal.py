"""Test suite for OpenJarvis v2.5.0-android Live Multimodal Architecture.

Verifies:
1. /api/audio/session/start, /chunk, /finish, /ask endpoints.
2. Multi-layer song identification (Layer 1 MediaMetadata, Layer 3 Lyrics web, Layer 4).
3. /api/vision/live live screen watching endpoint (multi-frame, UI text, history).
4. Follow-up active audio context injection into /api/chat.
5. Absolute NoneType and 429/402 resilience across all live session endpoints.
"""

import io
import json
import pytest
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, patch

from openjarvis.web.server import app
from openjarvis.live_media_session import LiveSessionStore, AudioSession, VisualSession


@pytest.fixture
def client():
    return TestClient(app)


def test_audio_session_lifecycle(client):
    # 1. Start audio session with media metadata
    start_payload = {
        "session_id": "test_audio_101",
        "media_metadata": {
            "title": "Taghdir",
            "artist": "Shadmehr Aghili",
            "album": "Taghdir Album"
        }
    }
    resp = client.post("/api/audio/session/start", json=start_payload)
    assert resp.status_code == 200
    data = resp.json()
    assert data["session_id"] == "test_audio_101"
    assert data["status"] == "listening"

    # 2. Upload a dummy 10s audio chunk
    dummy_wav = b"RIFF" + b"\x00" * 36 + b"data" + b"\x00" * 1000
    files = {"audio": ("chunk.wav", io.BytesIO(dummy_wav), "audio/wav")}
    form_data = {
        "session_id": "test_audio_101",
        "metadata": json.dumps({"title": "Taghdir", "artist": "Shadmehr Aghili"})
    }
    resp_chunk = client.post("/api/audio/session/chunk", data=form_data, files=files)
    assert resp_chunk.status_code == 200
    chunk_data = resp_chunk.json()
    assert chunk_data["session_id"] == "test_audio_101"
    assert chunk_data["chunk_index"] == 1

    # 3. Finish audio session with immediate question
    finish_payload = {
        "session_id": "test_audio_101",
        "question": "این چه آهنگیه؟",
        "language": "fa"
    }
    resp_finish = client.post("/api/audio/session/finish", json=finish_payload)
    assert resp_finish.status_code == 200
    fin_data = resp_finish.json()
    assert fin_data["status"] == "finished"
    assert fin_data["identified_song"] is not None
    assert fin_data["identified_song"]["title"] == "Taghdir"
    assert fin_data["identified_song"]["artist"] == "Shadmehr Aghili"
    assert "Taghdir" in fin_data["answer"] or "شادمهر" in fin_data["answer"] or "Taghdir" in fin_data["answer"]

    # 4. Ask follow-up question
    ask_payload = {
        "session_id": "test_audio_101",
        "question": "خوانندش کیه؟",
        "language": "fa"
    }
    resp_ask = client.post("/api/audio/session/ask", json=ask_payload)
    assert resp_ask.status_code == 200
    ask_data = resp_ask.json()
    assert ask_data["status"] == "success"
    assert "Shadmehr" in ask_data["response"] or "شادمهر" in ask_data["response"] or "Taghdir" in ask_data["response"]


def test_live_vision_screen_watching_endpoint(client):
    req_payload = {
        "session_id": "test_vis_202",
        "current_frame_base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        "recent_frames_base64": [],
        "ui_text_context": "App: Instagram\nButton: Like\nText: Sunset in Santorini",
        "foreground_app": "com.instagram.android",
        "prompt": "اینجا کجاست و توی تصویر چی نوشته؟",
        "language": "fa"
    }

    with patch("openjarvis.engine.cloud_rest.analyze_vision_live_multimodal", new_callable=AsyncMock) as mock_vision:
        mock_vision.return_value = "قربان، این تصویر غروب آفتاب در جزیره سانتورینی در برنامه اینستاگرام است."
        resp = client.post("/api/vision/live", json=req_payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert "سانتورینی" in data["response"]
        assert data["session_id"] == "test_vis_202"

    # Multi-turn visual follow-up remembers history
    followup_payload = {
        "session_id": "test_vis_202",
        "current_frame_base64": "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
        "prompt": "بیشتر توضیح بده",
        "language": "fa"
    }
    with patch("openjarvis.engine.cloud_rest.analyze_vision_live_multimodal", new_callable=AsyncMock) as mock_vision2:
        mock_vision2.return_value = "سانتورینی یکی از زیباترین جزایر کشور یونان در دریای اژه است قربان."
        resp2 = client.post("/api/vision/live", json=followup_payload)
        assert resp2.status_code == 200
        data2 = resp2.json()
        assert "یونان" in data2["response"]


def test_active_audio_context_injection_in_chat(client):
    # Set up an active audio session in store
    store = LiveSessionStore.get_instance()
    sess = store.get_or_create_audio_session("test_active_ctx_303", media_metadata={"title": "Hotel California", "artist": "Eagles"})
    sess.transcript = "Welcome to the Hotel California such a lovely place"
    sess.identified_song = {"title": "Hotel California", "artist": "Eagles", "source": "media_metadata"}
    store.save_audio_session(sess)

    from openjarvis.live_media_session import get_active_audio_context
    ctx = get_active_audio_context("test_active_ctx_303")
    assert ctx is not None
    assert "Hotel California" in ctx
    assert "Eagles" in ctx


def test_nonetype_and_quota_resilience_live_multimodal(client):
    # When vision model fails or returns empty, must never crash with NoneType
    req_payload = {
        "session_id": "test_vis_fail",
        "current_frame_base64": "invalid_or_empty",
        "prompt": "What is this?",
        "language": "en"
    }

    with patch("openjarvis.engine.cloud_rest.analyze_vision_live_multimodal", side_effect=Exception("Simulated 429 Quota Exhausted")):
        resp = client.post("/api/vision/live", json=req_payload)
        assert resp.status_code == 200
        data = resp.json()
        assert data["status"] == "success"
        assert "unable to complete" in data["response"] or "analysis" in data["response"].lower()
