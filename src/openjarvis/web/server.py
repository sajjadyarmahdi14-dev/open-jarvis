"""Lightweight FastAPI web server for Jarvis Mobile Web UI.

Provides REST endpoints for chat, tool execution, voice STT, and TTS.
"""

from __future__ import annotations

import logging
import os
import threading
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse
from pydantic import BaseModel

from openjarvis.core.config import _is_android, load_config
from openjarvis.core.env_config import (
    get_config_status,
    load_all_env_and_config,
    save_persistent_config,
)
from openjarvis.core.events import EventBus
from openjarvis.core.registry import AgentRegistry, ToolRegistry
from openjarvis.core.types import Conversation, Message, Role
from openjarvis.engine import get_engine
from openjarvis.intelligence import register_builtin_models
from openjarvis.tools._paths import file_tool_kwargs
from openjarvis.tools._stubs import BaseTool

logger = logging.getLogger(__name__)

# Ensure persistent configuration and .env candidates are loaded on server startup
load_all_env_and_config()

STATIC_DIR = Path(__file__).parent / "static"

app = FastAPI(title="Jarvis Mobile Web Server", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class ChatRequest(BaseModel):
    message: str
    history: Optional[List[Dict[str, str]]] = None
    tts: Optional[bool] = False


class TTSRequest(BaseModel):
    text: str


class VoiceRequest(BaseModel):
    tts: Optional[bool] = False


class ConfigUpdateRequest(BaseModel):
    openrouter_api_key: Optional[str] = None
    gemini_api_key: Optional[str] = None
    openrouter_model: Optional[str] = None
    gemini_model: Optional[str] = None
    active_model: Optional[str] = None


_cached_agent = None
_cached_tools = None


def _get_or_create_agent():
    """Initialize and cache the Android ReAct agent."""
    global _cached_agent, _cached_tools
    if _cached_agent is not None:
        return _cached_agent

    load_all_env_and_config()
    cfg_status = get_config_status()

    config = load_config()
    register_builtin_models()

    resolved = get_engine(config, "cloud")
    if resolved is None:
        raise RuntimeError(
            "No cloud inference engine available. Please open Settings (⚙️) in the top bar "
            "and configure your OpenRouter or Gemini API key."
        )

    engine_name, engine = resolved

    # Trigger registrations
    import openjarvis.agents  # noqa: F401
    import openjarvis.tools  # noqa: F401

    agent_cls = (
        AgentRegistry.get("react")
        if AgentRegistry.contains("react")
        else AgentRegistry.get("native_react")
    )

    # Gather all android tools + common tools
    tool_names = [k for k in ToolRegistry.keys() if k.startswith("android_")]
    for extra in ("calculator", "shell_exec", "web_search", "think", "repl", "file_read"):
        if ToolRegistry.contains(extra) and extra not in tool_names:
            tool_names.append(extra)

    tools: List[BaseTool] = []
    for tname in tool_names:
        if ToolRegistry.contains(tname):
            tcls = ToolRegistry.get(tname)
            if isinstance(tcls, type) and issubclass(tcls, BaseTool):
                tools.append(tcls(**file_tool_kwargs(tname, config)))
            elif isinstance(tcls, BaseTool):
                tools.append(tcls)

    _cached_tools = tools
    bus = EventBus(record_history=False)

    model = cfg_status.get("active_model") or "google/gemma-3-27b-it:free"

    agent = agent_cls(
        engine=engine,
        model=model,
        tools=tools,
        bus=bus,
        max_turns=config.agent.max_turns,
        interactive=False,
    )
    _cached_agent = agent
    return _cached_agent


def _speak_async(text: str) -> None:
    """Invoke Android TTS in a background thread so web response is immediate."""
    def _run():
        try:
            from openjarvis.speech.android_tts import AndroidTTS
            AndroidTTS().speak(text)
        except Exception as exc:
            logger.debug("Android TTS failed: %s", exc)

    threading.Thread(target=_run, daemon=True).start()


@app.get("/", response_class=HTMLResponse)
async def serve_index():
    index_file = STATIC_DIR / "index.html"
    if not index_file.exists():
        raise HTTPException(status_code=404, detail="Index HTML not found")
    return FileResponse(index_file)


@app.get("/manifest.json")
async def serve_manifest():
    manifest_file = STATIC_DIR / "manifest.json"
    if not manifest_file.exists():
        raise HTTPException(status_code=404, detail="Manifest not found")
    return FileResponse(manifest_file, media_type="application/manifest+json")


@app.get("/health")
@app.get("/api/health")
async def health_check():
    return {"status": "ok"}


@app.get("/api/config")
async def get_config():
    """Return current configuration, models, and masked keys."""
    return get_config_status()


@app.post("/api/config")
async def update_config(req: ConfigUpdateRequest):
    """Save persistent configuration and hot-reload the inference engine."""
    global _cached_agent, _cached_tools

    updated_status = save_persistent_config(
        openrouter_api_key=req.openrouter_api_key,
        gemini_api_key=req.gemini_api_key,
        openrouter_model=req.openrouter_model,
        gemini_model=req.gemini_model,
        active_model=req.active_model,
    )

    # Invalidate cached agent and tools so the next request uses the fresh engine
    _cached_agent = None
    _cached_tools = None

    try:
        _get_or_create_agent()
        return {
            "success": True,
            "config": updated_status,
            "message": f"Engine hot-reloaded with {updated_status['active_model']}",
        }
    except Exception as exc:
        return {
            "success": True,
            "config": updated_status,
            "warning": f"Config saved, but engine check reported: {exc}",
        }


@app.get("/api/status")
async def get_status():
    import openjarvis.tools  # noqa: F401
    from openjarvis.tools.android_tools import _run_termux_cmd

    battery_info = "Unknown"
    ok, bat_out = _run_termux_cmd(["termux-battery-status"])
    if ok and bat_out:
        try:
            import json
            data = json.loads(bat_out)
            pct = data.get("percentage")
            status = data.get("status", "")
            battery_info = f"{pct}% ({status})"
        except Exception:
            battery_info = bat_out

    cfg_status = get_config_status()

    return {
        "status": "online",
        "platform": "android" if _is_android() else "host",
        "model": cfg_status["active_model"],
        "provider": cfg_status["active_provider"],
        "has_key": cfg_status["has_openrouter_key"] or cfg_status["has_gemini_key"],
        "battery": battery_info,
        "tools_count": len([k for k in ToolRegistry.keys() if k.startswith("android_")]),
    }


@app.post("/api/chat")
async def chat_endpoint(req: ChatRequest):
    try:
        agent = _get_or_create_agent()
    except Exception as exc:
        return {"error": str(exc), "response": f"Failed to initialize engine: {exc}"}

    context = None
    if req.history:
        conv = Conversation()
        for item in req.history:
            role = Role.USER if item.get("role") == "user" else Role.ASSISTANT
            conv.messages.append(Message(role=role, content=item.get("content", "")))
        from openjarvis.agents._stubs import AgentContext
        context = AgentContext(conversation=conv)

    try:
        result = agent.run(input=req.message, context=context)
        tool_calls = [
            {
                "name": tr.tool_name,
                "content": tr.content,
                "success": tr.success,
            }
            for tr in result.tool_results
        ]

        if req.tts and result.content:
            _speak_async(result.content)

        return {
            "response": result.content,
            "tool_calls": tool_calls,
            "turns": result.turns,
        }
    except Exception as exc:
        logger.exception("Agent run failed")
        return {"error": str(exc), "response": f"Error processing request: {exc}"}


@app.post("/api/voice")
async def voice_endpoint(req: VoiceRequest):
    """Record audio via Termux microphone, transcribe via STT, and run chat."""
    import tempfile
    from openjarvis.speech.voice_io import _record_termux
    from openjarvis.speech.gemini_stt import GeminiSpeechBackend

    with tempfile.NamedTemporaryFile(suffix=".m4a", delete=False) as tmp:
        audio_path = Path(tmp.name)

    try:
        # Record 4 seconds of audio via Termux mic
        recorded = _record_termux(audio_path, duration=4.0)
        if not recorded or not audio_path.exists() or audio_path.stat().st_size == 0:
            return {"error": "Microphone recording failed. Ensure Termux:API audio permission is granted."}

        stt = GeminiSpeechBackend()
        transcription = stt.transcribe(audio_path).strip()
        if not transcription:
            return {"error": "Could not transcribe audio (no speech detected)."}

        # Run chat with transcription
        chat_req = ChatRequest(message=transcription, tts=req.tts)
        chat_res = await chat_endpoint(chat_req)
        return {
            "transcription": transcription,
            "response": chat_res.get("response", ""),
            "tool_calls": chat_res.get("tool_calls", []),
        }
    except Exception as exc:
        return {"error": f"Voice pipeline error: {exc}"}
    finally:
        if audio_path.exists():
            try:
                audio_path.unlink()
            except OSError:
                pass


@app.post("/api/tts")
async def tts_endpoint(req: TTSRequest):
    if not req.text:
        return {"success": False, "error": "Text is required"}
    _speak_async(req.text)
    return {"success": True}


@app.get("/api/camera/latest")
async def get_latest_camera():
    """Serve the latest camera photo captured by android_camera_capture."""
    cam_file = Path("/tmp/jarvis_cam.jpg")
    if not cam_file.exists():
        raise HTTPException(status_code=404, detail="No camera photo captured yet")
    return FileResponse(cam_file, media_type="image/jpeg")


@app.post("/api/tools/{tool_name}")
async def direct_tool_endpoint(tool_name: str, params: Optional[Dict[str, Any]] = None):
    """Directly execute an Android tool (e.g. for quick action chips)."""
    import openjarvis.tools  # noqa: F401
    if not ToolRegistry.contains(tool_name):
        raise HTTPException(status_code=404, detail=f"Tool '{tool_name}' not found")

    params = params or {}
    tcls = ToolRegistry.get(tool_name)
    config = load_config()
    tool_instance = tcls(**file_tool_kwargs(tool_name, config)) if isinstance(tcls, type) else tcls

    try:
        res = tool_instance.execute(**params)
        return {
            "success": res.success,
            "content": res.content,
            "tool_name": tool_name,
        }
    except Exception as exc:
        return {
            "success": False,
            "content": f"Execution failed: {exc}",
            "tool_name": tool_name,
        }


def run_server(host: str = "127.0.0.1", port: int = 8765):
    """Start uvicorn server."""
    import uvicorn
    uvicorn.run(app, host=host, port=port, log_level="warning")


if __name__ == "__main__":
    run_server()
