"""Tests for v2.6.1 Critical Performance & Voice Engine Recovery."""

from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path
from unittest.mock import MagicMock, patch

import httpx
import pytest
from fastapi.testclient import TestClient

from openjarvis.core.types import Message, Role
from openjarvis.engine.cloud_rest import (
    FREE_MODEL_FALLBACK_POOL,
    VOICE_FAST_MODEL_POOL,
    _is_reasoning_model_id,
    _select_candidate_models,
    stream_full_openai_compat_rest,
)
from openjarvis.security_vault import SecurityEvidenceVault
from openjarvis.web import server


@pytest.fixture
def client():
    return TestClient(server.app)


def test_voice_fast_model_selection_prioritizes_non_reasoning_models():
    assert FREE_MODEL_FALLBACK_POOL[0] == "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
    assert not _is_reasoning_model_id(VOICE_FAST_MODEL_POOL[0])

    selected = _select_candidate_models(
        base_url="https://openrouter.ai/api/v1",
        api_key="test-key",
        model="nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        voice_fast=True,
        is_async=True,
    )
    assert len(selected) <= 5
    assert not _is_reasoning_model_id(selected[0])
    assert "openrouter/free" not in selected


def test_chat_skips_web_search_and_app_bloat_on_normal_queries(monkeypatch, client):
    import openjarvis.web_intel as web_intel

    called_urls = []

    async def fail_search_images(*args, **kwargs):
        called_urls.append("search_images")
        return [{"url": "https://example.com/a.jpg", "caption": "a"}]

    async def fail_web_search(*args, **kwargs):
        called_urls.append("web_search")
        return [{"title": "x", "snippet": "y", "url": "https://example.com"}]

    monkeypatch.setattr(web_intel, "search_images", fail_search_images)
    monkeypatch.setattr(web_intel, "web_search", fail_web_search)

    # Normal Persian phrase containing substring "عکس" inside "برعکس", plus camera capture
    for msg in ["برعکسش کن ببینم چطوره", "عکس بگیر سریع", "tell me a short joke"]:
        res_imgs, prefix = asyncio.run(server._maybe_fetch_photo_intel(msg))
        assert res_imgs == []
        assert prefix == ""
    assert called_urls == []

    # Explicit photo lookup still runs concurrently
    res_imgs, prefix = asyncio.run(server._maybe_fetch_photo_intel("show me a photo of Saturn"))
    assert len(res_imgs) == 1
    assert "REAL WEB SEARCH RESULTS" in prefix
    assert "search_images" in called_urls and "web_search" in called_urls

    # Device apps prompt injection only triggers on app-related prompts
    monkeypatch.setattr(server, "_device_apps", [{"name": "Telegram", "pkg": "org.telegram.messenger"}])
    assert server._should_inject_device_apps("what is the speed of light") is False
    assert server._should_inject_device_apps("open telegram") is True
    assert server._should_inject_device_apps("برنامه تلگرام رو باز کن") is True


def test_stream_full_openai_compat_rest_fails_over_on_keepalive_timeout():
    tried_models = []

    class FakeResp:
        def __init__(self, model_name: str):
            self.model_name = model_name
            self.status_code = 200
            self.is_success = True
            self.text = ""

        async def aread(self):
            return b""

        async def aiter_lines(self):
            if "gemma-3-27b" in self.model_name:
                # Simulate OpenRouter keepalive comments forever (would hang without pre-token deadline!)
                for _ in range(20):
                    yield ": OPENROUTER PROCESSING"
                    await asyncio.sleep(0.15)
            else:
                yield 'data: {"choices":[{"delta":{"content":"Hello sir."},"finish_reason":"stop"}]}'
                yield "data: [DONE]"

    class FakeStreamCtx:
        def __init__(self, model_name: str):
            self.model_name = model_name

        async def __aenter__(self):
            return FakeResp(self.model_name)

        async def __aexit__(self, exc_type, exc, tb):
            return False

    class FakeAsyncClient:
        def stream(self, method, url, json=None, headers=None, timeout=None):
            model_name = (json or {}).get("model", "")
            tried_models.append(model_name)
            return FakeStreamCtx(model_name)

    async def _run():
        t0 = time.monotonic()
        chunks = []
        async for sc in stream_full_openai_compat_rest(
            async_client=FakeAsyncClient(),
            base_url="https://openrouter.ai/api/v1",
            api_key="test-key",
            model="nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
            messages=[Message(role=Role.USER, content="hi")],
            voice_fast=True,
            first_token_timeout_s=1.0,
        ):
            if sc.content:
                chunks.append(sc.content)
        return chunks, time.monotonic() - t0

    chunks, elapsed = asyncio.run(_run())
    assert chunks == ["Hello sir."]
    assert len(tried_models) >= 2
    assert elapsed < 3.0


def test_stt_async_uses_single_concurrent_bilingual_pass_without_sequential_fallbacks():
    import wave
    import io
    import struct
    import types

    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(16000)
        wf.writeframes(struct.pack("<800h", *([400] * 800)))
    wav_bytes = buf.getvalue()

    calls = []

    class FakeRecognizer:
        def record(self, src):
            return b"audio"

        def recognize_google(self, audio, language="en-US"):
            calls.append(language)
            if language == "fa-IR":
                return ""
            return "open calculator"

        def recognize_whisper(self, audio):
            raise AssertionError("Sequential whisper fallback must not run in fast /api/stt path")

    fake_sr = types.SimpleNamespace(
        Recognizer=FakeRecognizer,
        AudioFile=lambda bio: MagicMock(__enter__=lambda s: bio, __exit__=lambda s, *a: False),
    )

    with patch.dict(sys.modules, {"speech_recognition": fake_sr}):
        transcript = asyncio.run(server._do_transcribe_async(wav_bytes, "auto"))

    assert transcript == "open calculator"
    assert set(calls) == {"fa-IR", "en-US"}
    assert "en-GB" not in calls


def test_telegram_vault_polling_loop_uses_running_loop_nonblocking(tmp_path):
    vault = SecurityEvidenceVault(base_dir=str(tmp_path))

    async def _run():
        vault.start_polling_loop()
        assert vault._polling_task is not None
        assert not vault._polling_task.done()
        vault._polling_task.cancel()
        try:
            await vault._polling_task
        except asyncio.CancelledError:
            pass

    asyncio.run(_run())


def test_edge_tts_ram_and_disk_cache_hits(monkeypatch, tmp_path):
    monkeypatch.setenv("OPENJARVIS_TTS_CACHE_DIR", str(tmp_path))
    server._EDGE_TTS_RAM_CACHE.clear()

    import edge_tts

    synth_count = 0

    class FakeCommunicate:
        __module__ = "edge_tts.communicate"

        def __init__(self, **kwargs):
            pass

        async def stream(self):
            nonlocal synth_count
            synth_count += 1
            yield {"type": "audio", "data": b"ID3FAKE_MP3_BYTES_" + b"\x00" * 64}

    monkeypatch.setattr(edge_tts, "Communicate", FakeCommunicate)

    r1 = asyncio.run(server._synthesize_edge_tts("Yes, sir?"))
    assert synth_count == 1
    assert r1.media_type == "audio/mpeg"

    # Second call hits RAM cache cleanly
    r2 = asyncio.run(server._synthesize_edge_tts("Yes, sir?"))
    assert synth_count == 1
    assert r2.body == r1.body

    # Clearing RAM cache still hits disk cache cleanly
    server._EDGE_TTS_RAM_CACHE.clear()
    r3 = asyncio.run(server._synthesize_edge_tts("Yes, sir?"))
    assert synth_count == 1
    assert r3.body == r1.body
