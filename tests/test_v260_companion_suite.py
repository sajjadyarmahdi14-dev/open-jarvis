"""Comprehensive test suite for OpenJarvis v2.6.0-android Flagship Companion Suite.

Verifies:
1. Quick Notes & Brain Dump SQLite Engine (/api/notes POST, GET ?today=true, DELETE) + 0-token auto-tagging
2. Executive Daily Briefing endpoint (/api/briefing) with resilient weather fallback & VPS telemetry
3. Hands-Free Language Coach & Pocket Interpreter (/api/language/turn) with 100% Male Edge-TTS voices
4. Strict Male Edge-TTS voice selection across EN/FA/DE (en-GB-RyanNeural, fa-IR-FaridNeural, de-DE-ConradNeural)
5. Clipboard Vault (/api/security/clipboard) & Watchdog Alert (/api/security/alert) Telegram forwarding
6. VPS CPU used_pct metric in /api/vps/status
7. Android static checks for v2.6.0 manifest, accessibility key filtering, Gradle version 2.6.0 (code 32), and zero Google TTS
"""

import re
import types
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from openjarvis.notes_store import NotesStore, auto_tag_note_category
from openjarvis.security_vault import SecurityEvidenceVault
import openjarvis.web.server as web_server
from openjarvis.web.server import app, select_male_edge_tts_voice


REPO_ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture
def client():
    return TestClient(app)


@pytest.fixture
def isolated_notes_db(tmp_path, monkeypatch):
    db_file = tmp_path / "test_notes.db"
    monkeypatch.setenv("OPENJARVIS_NOTES_DB", str(db_file))
    old_instance = NotesStore._instance
    NotesStore._instance = None
    try:
        yield db_file
    finally:
        NotesStore._instance = None
        if old_instance is not None:
            NotesStore._instance = old_instance


def test_auto_tag_note_category_zero_tokens():
    assert auto_tag_note_category("Buy coffee filters and email Reza tomorrow") == "task"
    assert auto_tag_note_category("ایده طراحی سیستم کش آفلاین") == "idea"
    assert auto_tag_note_category('"Simplicity is the ultimate sophistication."') == "quote"
    assert auto_tag_note_category("Sunny afternoon walk") == "general"
    assert auto_tag_note_category("Random text", explicit_category="task") == "task"


def test_notes_crud_and_today_filter(client, isolated_notes_db):
    r1 = client.post("/api/notes", json={"text": "Remind me to pay server invoice"})
    assert r1.status_code == 200
    d1 = r1.json()
    assert d1["status"] == "saved"
    assert d1["category"] == "task"
    note_id_1 = d1["id"]

    r2 = client.post("/api/notes", json={"text": "Brainstorm idea for local voice cache"})
    assert r2.status_code == 200
    assert r2.json()["category"] == "idea"

    r3 = client.post("/api/notes", json={"text": "Just a quick general memo"})
    assert r3.status_code == 200
    assert r3.json()["category"] == "general"

    list_resp = client.get("/api/notes?today=true&limit=20")
    assert list_resp.status_code == 200
    payload = list_resp.json()
    assert payload["count"] == 3
    assert payload["today"] is True
    assert len(payload["notes"]) == 3

    del_resp = client.delete(f"/api/notes/{note_id_1}")
    assert del_resp.status_code == 200
    assert del_resp.json()["deleted"] is True

    list_after = client.get("/api/notes?today=true&limit=20").json()
    assert list_after["count"] == 2


def test_notes_telegram_forwarding_mock(client, isolated_notes_db, monkeypatch):
    sent_messages = []

    async def fake_send_telegram_text(self, text, parse_mode="HTML"):
        sent_messages.append({"text": text, "parse_mode": parse_mode})
        return {"status": "sent", "forwarded": True}

    monkeypatch.setattr(SecurityEvidenceVault, "send_telegram_text", fake_send_telegram_text)

    resp = client.post(
        "/api/notes",
        json={"text": "ایده ساخت ربات تلگرام هوشمند", "forward_telegram": True},
    )
    assert resp.status_code == 200
    data = resp.json()
    assert data["category"] == "idea"
    assert data["telegram"]["forwarded"] is True
    assert len(sent_messages) == 1
    assert "JARVIS BRAIN DUMP [IDEA]" in sent_messages[0]["text"]


def test_executive_briefing_endpoint_resilient(client, isolated_notes_db, monkeypatch):
    client.post("/api/notes", json={"text": "Call Ali at 4pm"})

    async def slow_or_failing_weather(city: str):
        raise RuntimeError("offline weather mock")

    monkeypatch.setattr("openjarvis.web_intel.get_weather", slow_or_failing_weather)

    resp = client.get("/api/briefing?city=Tehran&language=en")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "ok"
    assert data["city"] == "Tehran"
    assert data["notes_today_count"] >= 1
    assert "vps" in data
    assert "used_pct" in data["vps"]["cpu"]
    assert "Tehran" in data["weather_summary_en"]


def test_select_male_edge_tts_voice_en_fa_de():
    assert select_male_edge_tts_voice("Good morning sir", language="en") == "en-GB-RyanNeural"
    assert select_male_edge_tts_voice("سلام قربان", language="fa") == "fa-IR-FaridNeural"
    assert select_male_edge_tts_voice("Guten Morgen", language="de") == "de-DE-ConradNeural"

    # Female Edge-TTS voice overrides MUST be coerced strictly to Male equivalents
    assert select_male_edge_tts_voice("Hallo", explicit_voice="de-DE-KatjaNeural") == "de-DE-ConradNeural"
    assert select_male_edge_tts_voice("Hello", explicit_voice="en-US-JennyNeural") == "en-GB-RyanNeural"
    assert select_male_edge_tts_voice("سلام", explicit_voice="fa-IR-DilaraNeural") == "fa-IR-FaridNeural"

    # Auto-detect German umlaut/words without explicit language
    assert select_male_edge_tts_voice("Wie heißt du und wie geht es dir heute?") == "de-DE-ConradNeural"


def test_tts_endpoint_uses_german_male_conrad(client, monkeypatch):
    captured = {}

    class DummyCommunicate:
        def __init__(self, text, voice, rate=None, pitch=None):
            captured["text"] = text
            captured["voice"] = voice
            captured["rate"] = rate
            captured["pitch"] = pitch

        async def stream(self):
            yield {"type": "audio", "data": b"ID3FAKE_MP3_" + b"\x00" * 300}

    monkeypatch.setitem(__import__("sys").modules, "edge_tts", types.SimpleNamespace(Communicate=DummyCommunicate))

    resp = client.post(
        "/api/tts",
        json={"text": "Guten Tag! Wie geht es Ihnen?", "language": "de"},
    )
    assert resp.status_code == 200
    assert captured["voice"] == "de-DE-ConradNeural"
    assert resp.content.startswith(b"ID3FAKE_MP3_")


def test_language_turn_practice_and_interpreter(client, monkeypatch):
    async def fake_companion_llm(messages, request=None, max_tokens=240, temperature=0.4):
        system_msg = messages[0].content
        if "Pocket Live Interpreter" in system_msg:
            return "Hello, how can I get to the train station?"
        if "Deutsch" in system_msg or "German" in system_msg:
            return "Sehr gut! Dein Satz war fast richtig. Wohin möchtest du heute gehen?"
        return "Good effort! How did your morning walk go?"

    monkeypatch.setattr(web_server, "_generate_companion_llm", fake_companion_llm)

    de_resp = client.post(
        "/api/language/turn",
        json={
            "mode": "practice",
            "target_language": "de",
            "user_text": "Ich gehe nach Hause",
            "session_id": "test_de_sess",
            "level": "A2",
        },
    )
    assert de_resp.status_code == 200
    de_data = de_resp.json()
    assert de_data["tts_voice"] == "de-DE-ConradNeural"
    assert de_data["target_language"] == "de"
    assert de_data["session_turns"] == 2
    assert "richtig" in de_data["reply"]

    interp_resp = client.post(
        "/api/language/turn",
        json={
            "mode": "interpreter",
            "target_language": "en",
            "user_text": "سلام، چطور می‌تونم به ایستگاه قطار برم؟",
            "session_id": "test_interp_sess",
        },
    )
    assert interp_resp.status_code == 200
    interp_data = interp_resp.json()
    assert interp_data["tts_voice"] == "en-GB-RyanNeural"
    assert "train station" in interp_data["reply"]


def test_security_clipboard_and_watchdog_alert_endpoints(client, monkeypatch):
    forwarded = []

    async def fake_tg(self, text, parse_mode="HTML"):
        forwarded.append(text)
        return {"status": "sent", "forwarded": True}

    monkeypatch.setattr(SecurityEvidenceVault, "send_telegram_text", fake_tg)

    clip_resp = client.post(
        "/api/security/clipboard",
        json={"text": "Server token backup verification note", "source": "android_clipboard"},
    )
    assert clip_resp.status_code == 200
    assert clip_resp.json()["telegram"]["forwarded"] is True

    alert_resp = client.post(
        "/api/security/alert",
        json={
            "alert_type": "vps_cpu",
            "title": "VPS High CPU Alert",
            "message": "VPS CPU load exceeded 92%",
            "severity": "warning",
        },
    )
    assert alert_resp.status_code == 200
    assert alert_resp.json()["telegram"]["forwarded"] is True
    assert len(forwarded) == 2


def test_vps_status_exposes_cpu_used_pct(client):
    resp = client.get("/api/vps/status")
    assert resp.status_code == 200
    cpu = resp.json().get("cpu", {})
    assert "used_pct" in cpu
    assert 0.0 <= float(cpu["used_pct"]) <= 100.0


def test_android_v260_static_configs_and_no_google_tts():
    acc_xml = REPO_ROOT / "android-app/app/src/main/res/xml/accessibility_service_config.xml"
    tree = ET.parse(acc_xml)
    root_attrib = tree.getroot().attrib
    ns = "{http://schemas.android.com/apk/res/android}"
    assert root_attrib.get(f"{ns}canRequestFilterKeyEvents") == "true"
    assert root_attrib.get(f"{ns}canTakeScreenshot") == "true"

    manifest_xml = (REPO_ROOT / "android-app/app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    assert ".FakeCallActivity" in manifest_xml
    assert 'android:showWhenLocked="true"' in manifest_xml
    assert 'android:turnScreenOn="true"' in manifest_xml

    gradle_kts = (REPO_ROOT / "android-app/app/build.gradle.kts").read_text(encoding="utf-8")
    assert "versionCode = 33" in gradle_kts
    assert 'versionName = "2.6.1"' in gradle_kts

    android_src = REPO_ROOT / "android-app/app/src/main"
    for kt_file in android_src.rglob("*.kt"):
        src_text = kt_file.read_text(encoding="utf-8")
        assert "android.speech.tts.TextToSpeech" not in src_text, f"Forbidden Google TTS in {kt_file}"
        assert '"jarvis_prefs"' not in src_text, f"Stale prefs file in {kt_file}"
        assert "185.204.1.230" not in src_text, f"Stale server IP in {kt_file}"
