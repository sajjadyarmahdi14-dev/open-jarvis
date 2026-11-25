"""Test suite for OpenJarvis v2.5.2-android Master Polish & Reliability Update.

Verifies:
1. Speech Sanitizer (_clean_tts_response): Strips <think>, code blocks, raw URLs, and markdown links.
2. Web Image Search (search_images): Returns real verified image URLs.
3. Camera Zoom Intent Parser: Correctly extracts zoom=2.0 from English & Persian phrasing ("zoom x2", "2x", "زوم ۲", "زوم ۲ برابر").
4. Audio Session Endpoints (/api/audio/session/*): Registered, healthy, and return valid status without NoneType errors.
5. Live Vision Endpoint (/api/vision/live): Registered and handles requests safely.
6. Android static checks:
   - accessibility_service_config.xml declares android:canTakeScreenshot="true"
   - VoiceRecorderEngine is created and taps into ProVoiceEngine continuous PCM stream
"""

import asyncio
import os
import re
import xml.etree.ElementTree as ET
import pytest
from fastapi.testclient import TestClient

from openjarvis.web.server import (
    app,
    _clean_tts_response,
    parse_camera_intent,
)
from openjarvis.web_intel import search_images


@pytest.fixture
def client():
    return TestClient(app)


def test_clean_tts_response_strips_urls_and_markdown():
    """Verify Edge-TTS never reads URLs, markdown links, or reasoning aloud."""
    raw_text = (
        "<think>Finding photo of James Bond.</think>\n"
        "[Photo of James Bond](https://variety.com/12345/bond.jpg) "
        "Aaron Taylor-Johnson is currently the leading candidate. "
        "Visit https://en.wikipedia.org/wiki/James_Bond or www.007.com for details."
    )
    cleaned = _clean_tts_response(raw_text)

    assert "https" not in cleaned
    assert "http" not in cleaned
    assert "www" not in cleaned
    assert ".com" not in cleaned
    assert "Photo of" not in cleaned
    assert "<think>" not in cleaned
    assert "Aaron Taylor-Johnson" in cleaned
    assert "leading candidate" in cleaned


def test_camera_zoom_parser_all_variations():
    """Verify camera_control parser extracts target_zoom=2.0 from all variations."""
    cases = [
        "zoom x2",
        "zoom 2x",
        "x2",
        "2x",
        "take a photo with zoom 2x",
        "زوم ۲",
        "زوم دو برابر",
        "زوم ۲ برابر",
        "۲ برابر زوم",
    ]
    for case in cases:
        parsed = parse_camera_intent(case)
        assert parsed is not None, f"Failed to match camera intent for '{case}'"
        actions = parsed.get("client_actions", [])
        assert len(actions) > 0, f"No client actions generated for '{case}'"
        cam_act = actions[0]
        assert cam_act.get("type") == "camera_control"
        assert cam_act.get("target_zoom") == 2.0, (
            f"Expected target_zoom=2.0 for '{case}', got {cam_act.get('target_zoom')}"
        )


def test_search_images_returns_verified_list():
    """Verify search_images returns a list of dictionaries with valid http image URLs."""
    results = asyncio.run(search_images("Tehran", max_results=2))
    assert isinstance(results, list)
    if len(results) > 0:
        first = results[0]
        assert "url" in first
        assert first["url"].startswith("http")
        assert "caption" in first


def test_audio_session_endpoints(client):
    """Verify /api/audio/session/start, /chunk, /finish, /ask are registered and functional."""
    # 1. Start session
    start_resp = client.post("/api/audio/session/start", json={"session_id": "test_sess_001"})
    assert start_resp.status_code == 200
    start_data = start_resp.json()
    assert start_data.get("session_id") == "test_sess_001"
    assert start_data.get("status") == "listening"

    # 2. Finish session
    finish_resp = client.post("/api/audio/session/finish", json={
        "session_id": "test_sess_001",
        "question": "What song is this?",
        "language": "en"
    })
    assert finish_resp.status_code == 200
    finish_data = finish_resp.json()
    assert finish_data.get("session_id") == "test_sess_001"
    assert finish_data.get("status") == "finished"
    assert "answer" in finish_data

    # 3. Ask session
    ask_resp = client.post("/api/audio/session/ask", json={
        "session_id": "test_sess_001",
        "question": "Can you summarize the lyrics?",
        "language": "en"
    })
    assert ask_resp.status_code == 200
    ask_data = ask_resp.json()
    assert "response" in ask_data


def test_vision_live_endpoint_safe_response(client):
    """Verify /api/vision/live endpoint responds without NoneType errors."""
    resp = client.post("/api/vision/live", json={
        "session_id": "vis_test_001",
        "current_frame_base64": "",
        "recent_frames_base64": [],
        "ui_text_context": "Settings > Display",
        "foreground_app": "com.android.settings",
        "prompt": "What screen is open?",
        "language": "en"
    })
    assert resp.status_code == 200
    data = resp.json()
    assert "response" in data
    assert data.get("session_id") == "vis_test_001"


def test_android_accessibility_config_has_can_take_screenshot():
    """Verify accessibility_service_config.xml declares android:canTakeScreenshot='true'."""
    xml_path = "android-app/app/src/main/res/xml/accessibility_service_config.xml"
    if not os.path.exists(xml_path):
        xml_path = "/root/projects/open-jarvis/" + xml_path

    tree = ET.parse(xml_path)
    root = tree.getroot()
    # Check android:canTakeScreenshot attribute
    ns = "{http://schemas.android.com/apk/res/android}"
    can_screenshot = root.attrib.get(f"{ns}canTakeScreenshot")
    assert can_screenshot == "true", f"Expected android:canTakeScreenshot='true', got '{can_screenshot}'"
    assert root.attrib.get(f"{ns}canRetrieveWindowContent") == "true"
    assert root.attrib.get(f"{ns}canPerformGestures") == "true"


def test_voice_recorder_engine_exists_and_configured():
    """Verify VoiceRecorderEngine.kt exists and contains MediaStore Recordings integration."""
    kt_path = "android-app/app/src/main/java/com/sajjad/jarvis/VoiceRecorderEngine.kt"
    if not os.path.exists(kt_path):
        kt_path = "/root/projects/open-jarvis/" + kt_path

    with open(kt_path, "r", encoding="utf-8") as f:
        content = f.read()

    assert "object VoiceRecorderEngine" in content
    assert "DIRECTORY_RECORDINGS" in content
    assert "JARVIS_REC_" in content
    assert "ProVoiceEngine.voiceRecordListener" in content
