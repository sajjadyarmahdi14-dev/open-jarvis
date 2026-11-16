"""Unit and integration tests for Phase 4 Android Omni-Control Suite."""

from __future__ import annotations

import importlib
import json
from unittest.mock import patch, MagicMock
from pathlib import Path
import pytest
from fastapi.testclient import TestClient

import openjarvis.tools.android_tools
from openjarvis.core.registry import ToolRegistry
from openjarvis.tools.android_tools import (
    AndroidContactsSearchTool,
    AndroidSmsSendTool,
    AndroidSmsInboxTool,
    AndroidPhoneCallTool,
    AndroidTelegramActionTool,
    AndroidSendEmailTool,
    AndroidCameraCaptureTool,
    AndroidGpsLocationTool,
    AndroidSetAlarmTool,
    AndroidWifiControlTool,
    AndroidWebSearchTool,
    AndroidBatteryTool,
    AndroidTorchTool,
    AndroidNotificationTool,
    AndroidToastTool,
    AndroidClipboardGetTool,
    AndroidClipboardSetTool,
    AndroidVolumeTool,
    AndroidVibrateTool,
    AndroidIntentTool,
    AndroidLaunchAppTool,
    AndroidMediaPlayerTool,
    _search_contacts_data,
)
from openjarvis.agents.native_react import REACT_SYSTEM_PROMPT
from openjarvis.web.server import app


ALL_22_TOOLS = [
    "android_battery",
    "android_torch",
    "android_clipboard_get",
    "android_clipboard_set",
    "android_toast",
    "android_notification",
    "android_volume",
    "android_vibrate",
    "android_intent",
    "android_launch_app",
    "android_media_player",
    "android_contacts_search",
    "android_sms_send",
    "android_sms_inbox",
    "android_phone_call",
    "android_telegram_action",
    "android_send_email",
    "android_camera_capture",
    "android_gps_location",
    "android_set_alarm",
    "android_wifi_control",
    "android_web_search",
]


@pytest.fixture(autouse=True)
def ensure_tools_registered():
    """Ensure tools are reloaded and registered even if conftest cleared the registry."""
    importlib.reload(openjarvis.tools.android_tools)


def test_registry_contains_all_22_tools():
    for name in ALL_22_TOOLS:
        assert ToolRegistry.contains(name), f"Missing tool in ToolRegistry: {name}"
        tcls = ToolRegistry.get(name)
        tool_obj = tcls() if isinstance(tcls, type) else tcls
        assert tool_obj.spec.name == name
        assert tool_obj.spec.description


def test_react_prompt_guidance():
    assert "android_contacts_search" in REACT_SYSTEM_PROMPT
    assert "android_phone_call" in REACT_SYSTEM_PROMPT
    assert "android_camera_capture" in REACT_SYSTEM_PROMPT
    assert "android_launch_app" in REACT_SYSTEM_PROMPT
    assert "android_set_alarm" in REACT_SYSTEM_PROMPT


def test_contacts_search_mock():
    mock_contacts = [
        {"name": "Alice Smith", "number": "+1234567890"},
        {"name": "Bob Jones", "number": "+1987654321"},
    ]
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, json.dumps(mock_contacts))):
        results = _search_contacts_data("alice")
        assert len(results) == 1
        assert results[0]["name"] == "Alice Smith"

        tool = AndroidContactsSearchTool()
        res = tool.execute(query="bob")
        assert res.success is True
        assert "Bob Jones" in res.content


def test_sms_send_with_contact_resolution():
    mock_contacts = [{"name": "Sara", "number": "09123456789"}]
    with patch("openjarvis.tools.android_tools._search_contacts_data", return_value=mock_contacts):
        with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
            tool = AndroidSmsSendTool()
            res = tool.execute(recipient="Sara", message="Hello from Jarvis")
            assert res.success is True
            assert "09123456789" in res.content
            mock_cmd.assert_called_with(["termux-sms-send", "-n", "09123456789", "Hello from Jarvis"])


def test_phone_call_with_contact_resolution():
    mock_contacts = [{"name": "Dad", "number": "+989120000000"}]
    with patch("openjarvis.tools.android_tools._search_contacts_data", return_value=mock_contacts):
        with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
            tool = AndroidPhoneCallTool()
            res = tool.execute(recipient="Dad")
            assert res.success is True
            assert "+989120000000" in res.content
            mock_cmd.assert_called_with(["termux-telephony-call", "+989120000000"])


def test_telegram_action_open():
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
        tool = AndroidTelegramActionTool()
        res = tool.execute(target="telegram")
        assert res.success is True
        called_args, _ = mock_cmd.call_args
        assert called_args[0] == [
            "am", "start", "--user", "0",
            "-a", "android.intent.action.VIEW",
            "-d", "tg://resolve?domain=telegram"
        ]


def test_launch_app_aliases():
    tool = AndroidLaunchAppTool()
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
        res = tool.execute(app="Minecraft")
        assert res.success is True
        mock_cmd.assert_called_with([
            "am", "start", "--user", "0",
            "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER",
            "-p", "com.mojang.minecraftpe"
        ])


def test_set_alarm():
    tool = AndroidSetAlarmTool()
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
        res = tool.execute(hour=7, minute=30, message="Wake up")
        assert res.success is True
        assert "7:30" in res.content


def test_wifi_control():
    tool = AndroidWifiControlTool()
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "SSID: HomeNet")):
        res = tool.execute(action="info")
        assert res.success is True
        assert "HomeNet" in res.content


def test_web_server_endpoints():
    client = TestClient(app)
    # Status
    res = client.get("/api/status")
    assert res.status_code == 200
    assert res.json()["tools_count"] >= 22

    # Direct tool endpoint
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, '{"percentage": 90, "status": "CHARGING"}')):
        tool_res = client.post("/api/tools/android_battery", json={})
        assert tool_res.status_code == 200
        assert "90%" in tool_res.json()["content"]

    # Camera latest endpoint (returns 404 or image if exists)
    cam_res = client.get("/api/camera/latest")
    assert cam_res.status_code in (200, 404)


def test_env_config_and_status():
    from openjarvis.core.env_config import (
        get_config_status,
        load_all_env_and_config,
        parse_env_file,
    )
    import tempfile

    with tempfile.NamedTemporaryFile("w+", suffix=".env", delete=False) as f:
        f.write("# comment\nTEST_OPENJARVIS_KEY=test_value_123\nANOTHER_KEY=\"quoted_val\"\n")
        tmp_path = Path(f.name)

    try:
        parsed = parse_env_file(tmp_path)
        assert parsed.get("TEST_OPENJARVIS_KEY") == "test_value_123"
        assert parsed.get("ANOTHER_KEY") == "quoted_val"
    finally:
        tmp_path.unlink()

    status = get_config_status()
    assert "has_openrouter_key" in status
    assert "active_model" in status


def test_config_api_endpoints():
    client = TestClient(app)

    # 1. GET /api/config
    get_res = client.get("/api/config")
    assert get_res.status_code == 200
    data = get_res.json()
    assert "has_openrouter_key" in data
    assert "active_model" in data

    # 2. POST /api/config
    post_res = client.post(
        "/api/config",
        json={"active_model": "google/gemma-3-27b-it:free"}
    )
    assert post_res.status_code == 200
    pdata = post_res.json()
    assert pdata["success"] is True
    assert pdata["config"]["active_model"] == "google/gemma-3-27b-it:free"


def test_sms_inbox():
    mock_sms = [
        {"sender": "+1234567890", "body": "Your verification code is 1234", "received": "2026-11-16 10:00"}
    ]
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, json.dumps(mock_sms))):
        tool = AndroidSmsInboxTool()
        res = tool.execute(limit=1)
        assert res.success is True
        assert "1234" in res.content
        assert "+1234567890" in res.content


def test_send_email():
    tool = AndroidSendEmailTool()
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
        res = tool.execute(to="test@example.com", subject="Test", body="Hello")
        assert res.success is True
        assert "test@example.com" in res.content


def test_gps_location():
    tool = AndroidGpsLocationTool()
    mock_loc = {"latitude": 35.6892, "longitude": 51.3890, "altitude": 1200, "accuracy": 15}
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, json.dumps(mock_loc))):
        res = tool.execute()
        assert res.success is True
        assert "35.6892" in res.content
        assert "maps" in res.content.lower()


def test_web_search():
    tool = AndroidWebSearchTool()
    with patch("openjarvis.tools.android_tools._run_termux_cmd", return_value=(True, "")) as mock_cmd:
        res = tool.execute(query="open jarvis ai", engine="google")
        assert res.success is True
        assert "open jarvis ai" in res.content
        mock_cmd.assert_called_with([
            "am", "start", "--user", "0",
            "-a", "android.intent.action.VIEW",
            "-d", "https://www.google.com/search?q=open%20jarvis%20ai"
        ])


