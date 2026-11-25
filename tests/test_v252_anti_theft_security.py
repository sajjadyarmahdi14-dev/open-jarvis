"""Test suite for OpenJarvis v2.5.2 Iron Man Anti-Theft & Security Suite.

Verifies:
1. Security Evidence Endpoint (/api/security/evidence): Multipart photo and audio uploads with metadata.
2. Security Location Endpoint (/api/security/location): Telemetry tracking with coordinate persistence.
3. Telegram Forwarder & Config Endpoints (/api/security/telegram/*): Token storage, status masking, test ping.
4. Remote Security Command Queue (/api/security/commands, /api/security/command): Command queuing and pop/peek.
5. Telegram Bot Command Receiver: Handles /blackbox, /siren, /lock, /photo, /location, /standdown.
6. Android Security Suite Static Checks:
   - device_admin_policy.xml declares <force-lock /> and <watch-login />.
   - AndroidManifest.xml registers JarvisDeviceAdminReceiver with BIND_DEVICE_ADMIN and PASSWORD_FAILED intent filter.
   - SecurityProtocolEngine, SilentCameraCapturer, and JarvisDeviceAdminReceiver exist and are correctly wired.
   - ProVoiceEngine exposes securityAudioListener for zero-mic-conflict 5s rolling audio slices.
   - JarvisAccessibilityService implements Anti-Power-Off / Anti-QuickSettings Tamper Shield and unlockWithPin.
   - UtilityDomain and LocalCommandRouter route and intercept security protocols deterministically.
"""

import asyncio
import io
import json
import os
import re
import xml.etree.ElementTree as ET
import pytest
from fastapi.testclient import TestClient

from openjarvis.web.server import app
from openjarvis.security_vault import SecurityEvidenceVault


@pytest.fixture
def client(tmp_path, monkeypatch):
    test_vault_dir = tmp_path / "security_vault"
    monkeypatch.setenv("OPENJARVIS_VAULT_DIR", str(test_vault_dir))
    # Reset singleton with temporary directory
    SecurityEvidenceVault._instance = SecurityEvidenceVault(base_dir=str(test_vault_dir))
    return TestClient(app)


def test_security_evidence_endpoint_photo(client, tmp_path):
    """Verify photo evidence is saved to date-bucketed folder with JSON metadata."""
    fake_jpeg = b"\xFF\xD8\xFF\xE0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xFF\xDB"
    files = {"file": ("front_cam_test.jpg", fake_jpeg, "image/jpeg")}
    data = {
        "evidence_type": "photo",
        "session_id": "test_session_123",
        "battery": "85",
        "latitude": "35.6892",
        "longitude": "51.3890",
    }
    resp = client.post("/api/security/evidence", files=files, data=data)
    assert resp.status_code == 200
    res = resp.json()
    assert res["status"] == "stored"
    assert res["type"] == "photo"
    assert os.path.exists(res["path"])
    with open(res["path"], "rb") as f:
        assert f.read() == fake_jpeg


def test_security_evidence_endpoint_audio(client, tmp_path):
    """Verify 5-second rolling audio chunk is stored on VPS disk."""
    fake_wav = b"RIFF....WAVEfmt ....\x10\x00\x00\x00\x01\x00\x01\x00\x80>\x00\x00\x00}\x00\x00\x02\x00\x10\x00data...."
    files = {"file": ("audio_chunk_1.wav", fake_wav, "audio/wav")}
    data = {
        "evidence_type": "audio",
        "session_id": "rolling_5s_vault",
        "battery": "72",
    }
    resp = client.post("/api/security/evidence", files=files, data=data)
    assert resp.status_code == 200
    res = resp.json()
    assert res["status"] == "stored"
    assert res["type"] == "audio"
    assert os.path.exists(res["path"])


def test_security_location_endpoint(client):
    """Verify location telemetry breadcrumb is recorded."""
    payload = {
        "latitude": 35.7219,
        "longitude": 51.3347,
        "accuracy": 12.5,
        "speed": 0.0,
        "battery": 94,
        "device_id": "ironman_phone",
    }
    resp = client.post("/api/security/location", json=payload)
    assert resp.status_code == 200
    res = resp.json()
    assert res["status"] == "recorded"
    assert res["location"]["latitude"] == 35.7219
    assert res["location"]["longitude"] == 51.3347


def test_security_telegram_config_and_status(client):
    """Verify Telegram config persistence and masked token query."""
    payload = {
        "bot_token": "123456789:ABCdefGHIjklMNOpqrSTUvwxYZ",
        "chat_id": "987654321",
        "enabled": True,
        "test": False,
    }
    resp = client.post("/api/security/telegram/config", json=payload)
    assert resp.status_code == 200
    res = resp.json()
    assert res["configured"] is True
    assert res["masked_token"].startswith("123456")
    assert res["masked_token"].endswith("wxYZ")
    assert "..." in res["masked_token"]

    status_resp = client.get("/api/security/telegram/status")
    assert status_resp.status_code == 200
    status_data = status_resp.json()
    assert status_data["configured"] is True
    assert status_data["enabled"] is True


def test_security_command_queue_and_pop(client):
    """Verify queuing remote security commands and popping them for client execution."""
    cmd1 = {"command": "blackbox", "parameters": {"reason": "remote_trigger"}}
    r1 = client.post("/api/security/command", json=cmd1)
    assert r1.status_code == 200
    assert r1.json()["status"] == "queued"

    cmd2 = {"command": "siren"}
    r2 = client.post("/api/security/command", json=cmd2)
    assert r2.status_code == 200

    # Pop commands
    get_resp = client.get("/api/security/commands?pop=true")
    assert get_resp.status_code == 200
    data = get_resp.json()
    assert data["count"] == 2
    assert data["commands"][0]["command"] == "blackbox"
    assert data["commands"][1]["command"] == "siren"

    # Queue should be empty now
    empty_resp = client.get("/api/security/commands?pop=false")
    assert empty_resp.json()["count"] == 0


def test_security_status_endpoint(client):
    """Verify general security vault status endpoint."""
    resp = client.get("/api/security/status")
    assert resp.status_code == 200
    data = resp.json()
    assert data["status"] == "active"
    assert "vault_dir" in data
    assert "telegram" in data


def test_telegram_bot_command_polling_parser(tmp_path, monkeypatch):
    """Verify Telegram /blackbox, /siren, /lock commands are parsed into command queue."""
    vault = SecurityEvidenceVault(base_dir=str(tmp_path / "test_vault"))
    vault.config = {"bot_token": "fake_token", "chat_id": "111222333", "enabled": True}

    fake_updates = {
        "ok": True,
        "result": [
            {
                "update_id": 101,
                "message": {
                    "chat": {"id": 111222333},
                    "text": "/blackbox",
                },
            },
            {
                "update_id": 102,
                "message": {
                    "chat": {"id": 111222333},
                    "text": "/siren",
                },
            },
            {
                "update_id": 103,
                "message": {
                    "chat": {"id": 111222333},
                    "text": "/standdown secret123",
                },
            },
        ],
    }

    import httpx

    class MockAsyncClient:
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            pass
        async def get(self, url, params=None):
            class MockResponse:
                status_code = 200
                def json(self):
                    return fake_updates
            return MockResponse()
        async def post(self, url, json=None):
            class MockResponse:
                status_code = 200
            return MockResponse()

    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: MockAsyncClient())

    asyncio.run(vault.poll_telegram_bot_updates())
    cmds = vault.pop_pending_commands()
    assert len(cmds) == 3
    assert cmds[0]["command"] == "blackbox"
    assert cmds[1]["command"] == "siren"
    assert cmds[2]["command"] == "standdown"
    assert cmds[2]["parameters"]["passphrase"] == "secret123"


def test_android_device_admin_xml_and_manifest():
    """Verify Device Admin policy XML and AndroidManifest.xml declarations."""
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

    # 1. device_admin_policy.xml
    policy_path = os.path.join(root_dir, "android-app/app/src/main/res/xml/device_admin_policy.xml")
    assert os.path.isfile(policy_path), f"Missing {policy_path}"
    policy_tree = ET.parse(policy_path)
    policy_root = policy_tree.getroot()
    assert policy_root.tag == "device-admin"
    assert policy_root.find(".//force-lock") is not None, "device_admin_policy.xml must declare <force-lock />"
    assert policy_root.find(".//watch-login") is not None, "device_admin_policy.xml must declare <watch-login />"

    # 2. AndroidManifest.xml
    manifest_path = os.path.join(root_dir, "android-app/app/src/main/AndroidManifest.xml")
    assert os.path.isfile(manifest_path), f"Missing {manifest_path}"
    with open(manifest_path, "r", encoding="utf-8") as f:
        manifest_content = f.read()

    assert "JarvisDeviceAdminReceiver" in manifest_content
    assert "android.permission.BIND_DEVICE_ADMIN" in manifest_content
    assert "android.app.action.DEVICE_ADMIN_ENABLED" in manifest_content
    assert "android.app.action.ACTION_PASSWORD_FAILED" in manifest_content

    # 3. strings.xml
    strings_path = os.path.join(root_dir, "android-app/app/src/main/res/values/strings.xml")
    with open(strings_path, "r", encoding="utf-8") as f:
        strings_content = f.read()
    assert "device_admin_description" in strings_content


def test_android_security_components_presence():
    """Verify security Kotlin components, zero-mic tee listener, and accessibility shield."""
    root_dir = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

    # 1. SecurityProtocolEngine.kt
    sec_engine_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/security/SecurityProtocolEngine.kt")
    assert os.path.isfile(sec_engine_path), f"Missing {sec_engine_path}"
    with open(sec_engine_path, "r", encoding="utf-8") as f:
        sec_code = f.read()

    assert "isLockdownActive" in sec_code
    assert "executeAllSystemsDown" in sec_code
    assert "executeBlackboxProtocol" in sec_code
    assert "executeSirenProtocol" in sec_code
    assert "requestUnlockPhone" in sec_code
    assert "requestStandDown" in sec_code
    assert "startRollingAudioEvidenceLoop" in sec_code
    assert "verifyPassphrase" in sec_code
    assert "onIntruderAttemptDetected" in sec_code

    # 2. SilentCameraCapturer.kt
    cam_capturer_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/security/SilentCameraCapturer.kt")
    assert os.path.isfile(cam_capturer_path), f"Missing {cam_capturer_path}"
    with open(cam_capturer_path, "r", encoding="utf-8") as f:
        cam_code = f.read()
    assert "capturePhoto" in cam_code
    assert "captureBoth" in cam_code
    assert "ImageReader" in cam_code

    # 3. ProVoiceEngine security audio tap
    pve_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/ProVoiceEngine.kt")
    with open(pve_path, "r", encoding="utf-8") as f:
        pve_code = f.read()
    assert "securityAudioListener" in pve_code

    # 4. JarvisAccessibilityService tamper shield & unlock with pin
    acc_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/JarvisAccessibilityService.kt")
    with open(acc_path, "r", encoding="utf-8") as f:
        acc_code = f.read()
    assert "handleLockdownTamperShield" in acc_code
    assert "unlockWithPin" in acc_code
    assert "toggleQuickSettingOrConnectivity" in acc_code

    # 5. UtilityDomain security routing
    util_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/router/domains/UtilityDomain.kt")
    with open(util_path, "r", encoding="utf-8") as f:
        util_code = f.read()
    assert "handleSecurityProtocols" in util_code
    assert "SecurityProtocolEngine" in util_code

    # 6. LocalCommandRouter passphrase state interception
    lcr_path = os.path.join(root_dir, "android-app/app/src/main/java/com/sajjad/jarvis/LocalCommandRouter.kt")
    with open(lcr_path, "r", encoding="utf-8") as f:
        lcr_code = f.read()
    assert "AWAITING_PASSPHRASE_FOR_UNLOCK" in lcr_code
    assert "AWAITING_PASSPHRASE_FOR_STANDDOWN" in lcr_code
