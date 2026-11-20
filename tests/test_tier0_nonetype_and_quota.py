import json
import pytest
from unittest.mock import MagicMock, patch
import httpx

from openjarvis.core.types import Message, Role
from openjarvis.engine.cloud_rest import (
    generate_openai_compat_rest,
    safe_extract_message_content,
    safe_extract_tool_calls,
    QUOTA_EXHAUSTED_REPLY_FA,
    QUOTA_EXHAUSTED_REPLY_EN,
)


def test_safe_extract_helpers_against_malformed_structures():
    # Null cases
    assert safe_extract_message_content(None) == ""
    assert safe_extract_message_content({}) == ""
    assert safe_extract_message_content({"choices": None}) == ""
    assert safe_extract_message_content({"choices": []}) == ""
    assert safe_extract_message_content({"choices": [None]}) == ""
    assert safe_extract_message_content({"choices": [{"message": None}]}) == ""
    assert safe_extract_message_content({"choices": [{"message": {"content": None}}]}) == ""

    # Valid case
    valid_data = {"choices": [{"message": {"content": "Hello Tony"}}]}
    assert safe_extract_message_content(valid_data) == "Hello Tony"

    # Tool calls null cases
    assert safe_extract_tool_calls(None) == []
    assert safe_extract_tool_calls({}) == []
    assert safe_extract_tool_calls({"choices": None}) == []
    assert safe_extract_tool_calls({"choices": [{"message": None}]}) == []
    assert safe_extract_tool_calls({"choices": [{"message": {"tool_calls": None}}]}) == []
    assert safe_extract_tool_calls({"choices": [{"message": {"tool_calls": [None]}}]}) == []

    # Valid tool call
    tc_data = {
        "choices": [
            {
                "message": {
                    "tool_calls": [
                        {
                            "id": "call_123",
                            "function": {"name": "android_launch_app", "arguments": "{\"app\":\"spotify\"}"},
                        }
                    ]
                }
            }
        ]
    }
    calls = safe_extract_tool_calls(tc_data)
    assert len(calls) == 1
    assert calls[0]["name"] == "android_launch_app"
    assert calls[0]["id"] == "call_123"


def test_generate_openai_compat_rest_quota_429_persian():
    mock_client = MagicMock(spec=httpx.Client)
    # Simulate HTTP 429 quota exhaustion on all models
    mock_resp = MagicMock(spec=httpx.Response)
    mock_resp.status_code = 429
    mock_resp.text = '{"error":{"code":429,"message":"Daily free request limit exceeded"}}'
    mock_resp.json.return_value = {"error": {"code": 429, "message": "Daily free request limit exceeded"}}
    mock_client.post.return_value = mock_resp

    messages = [Message(role=Role.USER, content="یه جوک برام بگو")]
    result = generate_openai_compat_rest(
        client=mock_client,
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="openrouter/free",
        messages=messages,
    )

    assert result["finish_reason"] == "quota_exhausted"
    assert result["content"] == QUOTA_EXHAUSTED_REPLY_FA


def test_generate_openai_compat_rest_quota_402_english():
    mock_client = MagicMock(spec=httpx.Client)
    # Simulate HTTP 402 payment required / credits exhausted
    mock_resp = MagicMock(spec=httpx.Response)
    mock_resp.status_code = 402
    mock_resp.text = '{"error":{"code":402,"message":"Payment required: free credits exhausted"}}'
    mock_resp.json.return_value = {"error": {"code": 402, "message": "Payment required"}}
    mock_client.post.return_value = mock_resp

    messages = [Message(role=Role.USER, content="tell me a joke")]
    result = generate_openai_compat_rest(
        client=mock_client,
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="openrouter/free",
        messages=messages,
    )

    assert result["finish_reason"] == "quota_exhausted"
    assert result["content"] == QUOTA_EXHAUSTED_REPLY_EN


def test_generate_openai_compat_rest_handles_choices_none():
    mock_client = MagicMock(spec=httpx.Client)
    # Model returns 200 OK with {"choices": None} or {"choices": [{"message": {"content": None}}]}
    mock_resp = MagicMock(spec=httpx.Response)
    mock_resp.status_code = 200
    mock_resp.is_success = True
    mock_resp.raise_for_status = MagicMock()
    mock_resp.json.return_value = {"choices": [{"message": {"content": None}}]}
    mock_client.post.return_value = mock_resp

    messages = [Message(role=Role.USER, content="What is the speed of sound?")]
    result = generate_openai_compat_rest(
        client=mock_client,
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        messages=messages,
    )

    # Must NEVER raise AttributeError: 'NoneType' object has no attribute 'strip' or 'get'
    assert isinstance(result, dict)
    assert "content" in result
    assert isinstance(result["content"], str)


def test_generate_openai_compat_rest_rotates_on_empty_content():
    mock_client = MagicMock(spec=httpx.Client)

    # First candidate returns 200 with blank content, second candidate returns valid answer
    resp_empty = MagicMock(spec=httpx.Response)
    resp_empty.status_code = 200
    resp_empty.is_success = True
    resp_empty.raise_for_status = MagicMock()
    resp_empty.json.return_value = {"choices": [{"message": {"content": "   "}}]}

    resp_valid = MagicMock(spec=httpx.Response)
    resp_valid.status_code = 200
    resp_valid.is_success = True
    resp_valid.raise_for_status = MagicMock()
    resp_valid.json.return_value = {
        "model": "meta-llama/llama-3.3-70b-instruct:free",
        "choices": [{"message": {"content": "Why did the scarecrow win an award? Because he was outstanding in his field!"}}],
    }

    mock_client.post.side_effect = [resp_empty, resp_valid]

    messages = [Message(role=Role.USER, content="tell me a joke")]
    result = generate_openai_compat_rest(
        client=mock_client,
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="openrouter/free",
        messages=messages,
    )

    assert result["content"] == "Why did the scarecrow win an award? Because he was outstanding in his field!"
    assert mock_client.post.call_count == 2


def test_api_chat_quota_error_persian_and_english():
    from fastapi.testclient import TestClient
    from openjarvis.web.server import app

    client = TestClient(app)

    # Patch _get_or_create_agent to simulate quota exhaustion exception
    with patch("openjarvis.web.server._get_or_create_agent") as mock_agent_getter:
        mock_agent = MagicMock()
        mock_agent.run.side_effect = RuntimeError("OpenRouter returned HTTP 429: Daily free request limit exceeded")
        mock_agent_getter.return_value = mock_agent

        # Persian test
        resp_fa = client.post("/api/chat", json={"message": "یه جوک بگو"})
        assert resp_fa.status_code == 200
        data_fa = resp_fa.json()
        assert data_fa["response"] == QUOTA_EXHAUSTED_REPLY_FA
        assert "NoneType" not in data_fa["response"]

        # English test
        resp_en = client.post("/api/chat", json={"message": "tell me a joke"})
        assert resp_en.status_code == 200
        data_en = resp_en.json()
        assert data_en["response"] == QUOTA_EXHAUSTED_REPLY_EN
        assert "NoneType" not in data_en["response"]


def test_api_chat_stream_quota_error():
    from fastapi.testclient import TestClient
    from openjarvis.web.server import app

    client = TestClient(app)

    with patch("openjarvis.web.server._get_or_create_agent") as mock_agent_getter:
        mock_agent = MagicMock()
        # Mock engine.stream_full to simulate quota failure
        mock_agent.engine.stream_full.side_effect = RuntimeError("HTTP 402 Payment required: Insufficient credits")
        mock_agent_getter.return_value = mock_agent

        resp = client.post("/api/chat/stream", json={"message": "tell me a joke"})
        assert resp.status_code == 200
        lines = [line for line in resp.text.split("\n") if line.strip()]
        assert len(lines) >= 1
        sentences = [json.loads(l).get("text", "") for l in lines if '"sentence"' in l]
        assert any(QUOTA_EXHAUSTED_REPLY_EN in s for s in sentences)
        assert not any("NoneType" in l for l in lines)

