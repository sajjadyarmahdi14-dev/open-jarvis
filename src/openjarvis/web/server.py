"""Lightweight FastAPI web server for Jarvis Mobile Web UI.

Provides REST endpoints for chat, tool execution, voice STT, and TTS.
"""

from __future__ import annotations

import asyncio
from datetime import datetime
import io
import logging
import os
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, File, Form, HTTPException, Query, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, Response
from pydantic import BaseModel

from openjarvis.core.config import _is_android, load_config
from openjarvis.core.env_config import (
    DEFAULT_OPENROUTER_MODEL,
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
import openjarvis.tools.android_tools  # noqa: F401

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
    device_battery: Optional[int] = None
    device_charging: Optional[bool] = None


class TTSRequest(BaseModel):
    text: str


class TTSAudioRequest(BaseModel):
    text: str
    voice: Optional[str] = None
    rate: Optional[str] = "+5%"
    pitch: Optional[str] = "-2Hz"


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
_device_apps: List[Dict[str, str]] = []


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

    model = cfg_status.get("active_model") or DEFAULT_OPENROUTER_MODEL
    if model == "openrouter/free":
        model = DEFAULT_OPENROUTER_MODEL

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


def _clean_tts_response(text: str) -> str:
    """Strip reasoning tokens <think>...</think> and markdown syntax for clean spoken voice."""
    import re
    if not text:
        return ""
    # Strip closed think tags
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", text, flags=re.IGNORECASE)
    # Strip unclosed think tag at the end if tokens were exhausted
    cleaned = re.sub(r"<think>[\s\S]*$", "", cleaned, flags=re.IGNORECASE)
    cleaned = re.sub(r"```[\s\S]*?```", "", cleaned)
    cleaned = re.sub(r"[*_#`]", "", cleaned)
    cleaned = cleaned.strip()
    if not cleaned and "<think>" in text:
        # If everything was inside <think>, extract the last complete sentence from the thinking block as a fallback
        think_content = re.sub(r"</?think>", "", text, flags=re.IGNORECASE).strip()
        sentences = [s.strip() for s in re.split(r"[.!?؛؟\n]+", think_content) if s.strip()]
        if sentences:
            cleaned = sentences[-1]
    return cleaned.strip()


def _speak_async(text: str) -> None:
    """Invoke Android TTS in a background thread so web response is immediate."""
    clean_text = _clean_tts_response(text)
    if not clean_text:
        return

    def _run():
        try:
            from openjarvis.speech.android_tts import AndroidTTS
            AndroidTTS().speak(clean_text)
        except Exception as exc:
            logger.debug("Android TTS failed: %s", exc)

    threading.Thread(target=_run, daemon=True).start()


def _handle_fast_path(
    message: str,
    device_battery: Optional[int] = None,
    device_charging: Optional[bool] = None,
) -> Optional[Dict[str, Any]]:
    """Instant local fast-path (<50ms) for high-frequency direct Android commands and greetings."""
    import re
    from datetime import datetime
    from openjarvis.tools.android_tools import (
        AndroidBatteryTool,
        AndroidLaunchAppTool,
        AndroidSetAlarmTool,
        AndroidTorchTool,
        AndroidVolumeTool,
    )

    msg = message.strip()
    msg_lower = msg.lower()
    is_persian = any("\u0600" <= c <= "\u06FF" for c in msg)

    # Strip wake-words and politeness tokens from start, middle, and end
    norm = msg_lower
    wake_patterns = [
        r"\b(?:hey|hi|hello|dear)?\s*jarvis\b",
        r"\b(?:سلام|هی)?\s*جارویس\b",
        r"\bplease\b",
        r"\b(?:لطفاً|لطفا)\b",
    ]
    for wp in wake_patterns:
        norm = re.sub(wp, " ", norm, flags=re.IGNORECASE)
    norm = re.sub(r"\s+", " ", norm).strip()

    cleaned = re.sub(r"[^\w\s\u0600-\u06FF]", "", norm).strip()
    raw_cleaned = re.sub(r"[^\w\s\u0600-\u06FF]", "", msg_lower).strip()

    # -1. Sleep / Standby Mode Triggers (<5ms match, ZERO LLM call!)
    sleep_terms = (
        "stop listening", "bye jarvis", "goodbye jarvis", "go to sleep",
        "jarvis sleep", "sleep mode", "standby", "that's all jarvis", "mute yourself",
        "دیگه گوش نده", "گوش نده", "خداحافظ جارویس", "فعلا جارویس", "جارویس بخواب",
        "بخواب", "استندبای", "خداحافظ"
    )
    if any(s in norm for s in sleep_terms):
        reply = (
            "به حالت استندبای می‌روم قربان. هر زمان نیاز داشتید جارویس را صدا بزنید."
            if is_persian
            else "Going into standby mode, sir. Just say Jarvis whenever you need me."
        )
        return {
            "response": reply,
            "tool_calls": [],
            "turns": 1,
            "client_actions": [{"type": "sleep"}],
            "fast_path": True,
            "status": "sleep",
        }

    # 0. Conversational greetings and wake checks (<5ms)
    greetings_en = {
        "", "hi", "hello", "hey", "jarvis", "hey jarvis", "hi jarvis", "dear jarvis",
        "good morning", "good afternoon", "good evening", "how are you",
        "are you there", "who are you", "what's up", "whats up", "status", "ping"
    }
    greetings_fa = {
        "", "سلام", "درود", "خوبی", "چطوری", "جارویس", "هی jarvis", "هی جارویس",
        "سلام جارویس", "هستی", "آنلاینی", "صبح بخیر", "عصر بخیر", "شب بخیر"
    }
    if raw_cleaned in {"jarvis", "hey jarvis", "hi jarvis", "dear jarvis", "جارویس", "سلام جارویس"} or not cleaned:
        if is_persian:
            return {
                "response": "در خدمت شما هستم قربان. تمام سیستم‌ها آنلاین و آماده هستند. چطور می‌توانم کمکتان کنم؟",
                "tool_calls": [],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }
        else:
            return {
                "response": "At your service, sir. All systems are online. How may I help you?",
                "tool_calls": [],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }

    if cleaned in greetings_en or raw_cleaned in greetings_en or norm in greetings_en:
        return {
            "response": "At your service, sir. All systems are online. How may I help you?",
            "tool_calls": [],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }
    if cleaned in greetings_fa or raw_cleaned in greetings_fa or norm in greetings_fa:
        return {
            "response": "در خدمت شما هستم قربان. تمام سیستم‌ها آنلاین و آماده هستند. چطور می‌توانم کمکتان کنم؟",
            "tool_calls": [],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # 1. Time and Date queries (<5ms)
    if re.search(r"\b(?:what(?:\s+is|\s*'s)?\s+the\s+time|what\s+time\s+is\s+it|current\s+time|time\s+now|ساعت\s*چنده|ساعت\s*چند\s*است|ساعت\s*الان|زمان\s*الان)\b", norm) or cleaned in {"time", "ساعت", "زمان"}:
        now_dt = datetime.now()
        reply = (
            f"قربان، ساعت هم‌اکنون {now_dt.strftime('%H:%M')} است."
            if is_persian
            else f"It is currently {now_dt.strftime('%I:%M %p')}, sir."
        )
        return {
            "response": reply,
            "tool_calls": [],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }
    if re.search(r"\b(?:what(?:\s+is|\s*'s)?\s+the\s+date|what\s+date\s+is\s+today|today(?:\s*'s)?\s+date|current\s+date|تاریخ\s*امروز|امروز\s*چندمه|امروز\s*چه\s*روزیه)\b", norm) or cleaned in {"date", "today", "امروز", "تاریخ"}:
        now_dt = datetime.now()
        reply = (
            f"قربان، امروز {now_dt.strftime('%A, %d %B %Y')} است."
            if is_persian
            else f"Today is {now_dt.strftime('%A, %B %d, %Y')}, sir."
        )
        return {
            "response": reply,
            "tool_calls": [],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # 2. Flashlight / Torch: English & Persian (Evaluated BEFORE app launching!)
    torch_terms = ("flashlight", "torch", "چراغ قوه", "چراغ‌قوه", "فلش")
    if any(t in norm for t in torch_terms):
        off_terms = ("off", "خاموش", "ببند", "قطع", "disable", "stop")
        toggle_terms = ("toggle", "عوض", "تغییر")
        if any(w in norm for w in off_terms):
            state = "off"
            reply = "چراغ قوه خاموش شد قربان." if is_persian else "Flashlight turned off, sir."
        elif any(w in norm for w in toggle_terms):
            state = "toggle"
            reply = "چراغ قوه تغییر وضعیت داد قربان." if is_persian else "Flashlight toggled, sir."
        else:
            state = "on"
            reply = "چراغ قوه روشن شد قربان." if is_persian else "Flashlight turned on, sir."

        res = AndroidTorchTool().execute(state=state)
        return {
            "response": reply,
            "tool_calls": [{"name": "android_torch", "content": f"Flashlight {state}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "torch", "state": state}],
            "fast_path": True,
        }

    # 3. Open / Launch App: English & Persian with Dynamic Device Apps Matching
    m_en = re.match(r"^(?:open|launch|start|run|play)\s+(?:the\s+)?([a-zA-Z0-9_\- ]+?)[.!?]*$", norm, re.IGNORECASE)
    m_fa = re.match(r"^(?:برنامه\s+|بازی\s+)?(.+?)\s*(?:رو|را)?\s*(?:باز\s*کن|اجرا\s*کن|بالا\s*بیار)[.!?]*$", norm)
    m_fa_prefix = re.match(r"^(?:باز\s*کن|اجرا\s*کن)\s+(?:برنامه\s+|بازی\s+)?(.+?)[.!?]*$", norm)

    app_target = None
    if m_en:
        app_target = m_en.group(1).strip()
    elif m_fa:
        app_target = m_fa.group(1).strip()
    elif m_fa_prefix:
        app_target = m_fa_prefix.group(1).strip()

    if not app_target and _device_apps and len(norm.split()) <= 2:
        for d_app in _device_apps:
            lbl = d_app.get("label", "").lower()
            if lbl and (lbl == norm or lbl == cleaned):
                app_target = d_app.get("label")
                break

    if app_target and len(app_target) <= 40:
        if app_target.lower() in ("camera", "cam", "دوربین"):
            app_target = "camera"

        pkg = ""
        target_lower = app_target.lower()
        for d_app in _device_apps:
            lbl = d_app.get("label", "").lower()
            p_name = d_app.get("package", "")
            if lbl == target_lower or target_lower in lbl or p_name.lower().endswith(f".{target_lower}"):
                pkg = p_name
                break

        if not pkg:
            res = AndroidLaunchAppTool().execute(app=app_target)
            pkg = res.metadata.get("package", "") if hasattr(res, "metadata") and res.metadata else ""

        reply = (
            f"چشم قربان، در حال باز کردن {app_target}."
            if is_persian
            else f"Right away, sir. Opening {app_target}."
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "android_launch_app", "content": f"Opening {app_target}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "launch_app", "target": app_target, "package": pkg}],
            "fast_path": True,
        }

    # 4. Battery status: English & Persian (Works remotely on VPS via device telemetry!)
    if re.search(r"\b(?:battery|battery\s+status|check\s+battery|how\s+much\s+battery|باتری|درصد\s*باتری|وضعیت\s*باتری|شارژ\s*باتری|شارژ\s*چقدره)\b", norm) and len(norm.split()) <= 5:
        if device_battery is not None and device_battery >= 0:
            chg_str = " (در حال شارژ)" if device_charging else ""
            chg_en = " (charging)" if device_charging else ""
            reply = (
                f"میزان شارژ باتری دستگاه شما {device_battery}% است{chg_str} قربان."
                if is_persian
                else f"Device battery is at {device_battery}%{chg_en}, sir."
            )
            return {
                "response": reply,
                "tool_calls": [],
                "turns": 1,
                "client_actions": [{"type": "battery_status", "percentage": device_battery, "charging": device_charging}],
                "fast_path": True,
            }
        res = AndroidBatteryTool().execute()
        reply = (
            f"گزارش باتری: {res.content}"
            if is_persian
            else f"Battery status, sir: {res.content}."
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "android_battery", "content": res.content, "success": res.success}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # 5. Volume adjustment
    if re.search(r"\b(?:volume\s+up|increase\s+volume|louder|صدا\s*(?:رو|را)?\s*زیاد\s*کن|صدا\s*بیشتر)\b", norm):
        res = AndroidVolumeTool().execute(stream="music", volume=12)
        reply = "صدای رسانه افزایش یافت قربان." if is_persian else "Volume increased, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "android_volume", "content": res.content, "success": res.success}],
            "turns": 1,
            "client_actions": [{"type": "volume", "action": "up"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:volume\s+down|decrease\s+volume|lower\s+volume|صدا\s*(?:رو|را)?\s*کم\s*کن|صدا\s*کمتر)\b", norm):
        res = AndroidVolumeTool().execute(stream="music", volume=5)
        reply = "صدای رسانه کاهش یافت قربان." if is_persian else "Volume decreased, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "android_volume", "content": res.content, "success": res.success}],
            "turns": 1,
            "client_actions": [{"type": "volume", "action": "down"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:mute|silent|بی\s*صدا|صدا\s*(?:رو|را)?\s*قطع\s*کن)\b", norm):
        res = AndroidVolumeTool().execute(stream="music", volume=0)
        reply = "صدا قطع شد قربان." if is_persian else "Audio muted, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "android_volume", "content": res.content, "success": res.success}],
            "turns": 1,
            "client_actions": [{"type": "volume", "action": "mute"}],
            "fast_path": True,
        }

    # 6. Set Alarm
    m_alarm_en = re.search(r"\b(?:set\s+(?:an?\s+)?alarm\s+(?:for|at)|alarm\s+at|wake\s+me\s+up\s+at)\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\b", norm)
    m_alarm_fa = re.search(r"\b(?:ساعت\s*(\d{1,2})(?::(\d{2}))?\s*(?:زنگ\s*بذار|آلارم\s*بذار|بیدارم\s*کن))\b", norm)
    if m_alarm_en or m_alarm_fa:
        h = 7
        m = 0
        if m_alarm_en:
            h = int(m_alarm_en.group(1))
            m = int(m_alarm_en.group(2) or 0)
            ampm = (m_alarm_en.group(3) or "").lower()
            if ampm == "pm" and h < 12:
                h += 12
            elif ampm == "am" and h == 12:
                h = 0
        elif m_alarm_fa:
            h = int(m_alarm_fa.group(1))
            m = int(m_alarm_fa.group(2) or 0)
        reply = (
            f"آلارم برای ساعت {h:02d}:{m:02d} تنظیم شد قربان."
            if is_persian
            else f"Alarm set for {h:02d}:{m:02d}, sir."
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "android_alarm", "content": f"Alarm set for {h:02d}:{m:02d}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "alarm", "hour": h, "minute": m, "message": "Jarvis Alarm"}],
            "fast_path": True,
        }

    # 7. Phone call: by phone number or contact name (e.g. "call dad", "call mom", "زنگ بزن به بابا")
    m_call_en = re.search(r"\b(?:call|phone|dial)\s+([a-zA-Z0-9_+ -]+?)[.!?]*$", norm)
    m_call_fa = re.search(r"\b(?:تماس\s+بگیر\s+با|زنگ\s+بزن\s+به)\s+(.+?)[.!?]*$", norm)
    if m_call_en or m_call_fa:
        target = (m_call_en.group(1) if m_call_en else m_call_fa.group(1)).strip()
        if target and len(target) <= 35 and target.lower() not in ("me", "back", "later", "it"):
            is_digits = bool(re.match(r"^[0-9+ \-]+$", target))
            reply = f"در حال برقراری تماس با {target} قربان." if is_persian else f"Calling {target}, sir."
            client_act = {"type": "call"}
            if is_digits:
                client_act["number"] = target.replace(" ", "").replace("-", "")
            else:
                client_act["name"] = target
            return {
                "response": reply,
                "tool_calls": [{"name": "android_call", "content": f"Calling {target}", "success": True}],
                "turns": 1,
                "client_actions": [client_act],
                "fast_path": True,
            }

    # 8. Open URL / Website
    m_url = re.search(r"\b(?:open\s+website|open\s+url|go\s+to)\s+(https?://[^\s]+|[a-zA-Z0-9.\-]+\.[a-z]{2,})\b", norm)
    if m_url:
        raw_url = m_url.group(1)
        full_url = raw_url if raw_url.startswith("http") else f"https://{raw_url}"
        reply = f"Opening {full_url}, sir."
        return {
            "response": reply,
            "tool_calls": [],
            "turns": 1,
            "client_actions": [{"type": "open_url", "url": full_url}],
            "fast_path": True,
        }

    return None


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


@app.get("/api/ping")
async def ping_endpoint():
    """Ultra-lightweight ping (<5ms) for connection status check without blocking."""
    return {"status": "ok", "time": time.time()}


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


@app.post("/api/device/apps")
async def register_device_apps(request: Request):
    """Store dynamically discovered apps synced in real-time from the Android device."""
    global _device_apps
    try:
        data = await request.json()
        if isinstance(data, list):
            _device_apps = data
            logger.info("Updated dynamic device apps index with %d packages", len(_device_apps))
            return {"status": "ok", "count": len(_device_apps)}
    except Exception as exc:
        logger.warning("register_device_apps error: %s", exc)
    return {"status": "error", "count": len(_device_apps)}


@app.get("/api/device/apps")
async def get_device_apps():
    """Retrieve current dynamic device apps index."""
    return {"status": "ok", "apps": _device_apps, "count": len(_device_apps)}


@app.post("/api/chat")
async def chat_endpoint(req: ChatRequest):
    # 0. Instant Local Fast-Path (<100ms) with device telemetry
    fast_res = _handle_fast_path(
        req.message,
        device_battery=req.device_battery,
        device_charging=req.device_charging,
    )
    if fast_res is not None:
        if req.tts and fast_res.get("response"):
            _speak_async(fast_res["response"])
        return fast_res

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
        # Run agent in background thread so async event loop and ping checks are never blocked!
        result = await asyncio.to_thread(agent.run, input=req.message, context=context)
        tool_calls = [
            {
                "name": tr.tool_name,
                "content": tr.content,
                "success": tr.success,
            }
            for tr in result.tool_results
        ]

        client_actions = []
        for tr in result.tool_results:
            meta = getattr(tr, "metadata", {}) or {}
            if "client_action" in meta:
                client_actions.append({
                    "type": meta["client_action"],
                    "target": meta.get("target") or meta.get("package") or "",
                    "package": meta.get("package", ""),
                })
            elif tr.tool_name == "android_launch_app":
                client_actions.append({
                    "type": "launch_app",
                    "target": req.message,
                })

        clean_content = _clean_tts_response(result.content) if result.content else ""
        if req.tts and clean_content:
            _speak_async(clean_content)

        return {
            "response": clean_content or result.content,
            "tool_calls": tool_calls,
            "turns": result.turns,
            "client_actions": client_actions,
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
        # Record 4 seconds of audio via Termux mic without blocking on stdin
        recorded = _record_termux(audio_path, duration=4.0, interactive=False)
        if not recorded:
            return {"error": "Microphone recording failed. Ensure Termux:API audio permission is granted."}

        if not audio_path.exists() or audio_path.stat().st_size == 0:
            audio_path.write_bytes(recorded)

        stt = GeminiSpeechBackend()
        res = stt.transcribe(recorded or audio_path, format="m4a")
        transcription = (res.text if hasattr(res, "text") else str(res)).strip()
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
        logger.exception("Voice pipeline error: %s", exc)
        return {"error": f"Voice pipeline error: {exc}"}
    finally:
        if audio_path.exists():
            try:
                audio_path.unlink()
            except OSError:
                pass


@app.post("/api/voice/upload")
async def voice_upload_endpoint(
    audio: UploadFile = File(...),
    tts: bool = Form(False),
):
    """Accept an audio recording directly from browser/app mic, transcribe, and chat."""
    from openjarvis.speech.gemini_stt import GeminiSpeechBackend

    try:
        audio_bytes = await audio.read()
        if not audio_bytes:
            return {"error": "Received empty audio file"}

        filename = audio.filename or "audio.webm"
        suffix = Path(filename).suffix.lstrip(".").lower()
        if not suffix:
            ct = (audio.content_type or "").lower()
            if "webm" in ct:
                suffix = "webm"
            elif "mp4" in ct or "m4a" in ct:
                suffix = "m4a"
            elif "ogg" in ct:
                suffix = "ogg"
            elif "wav" in ct:
                suffix = "wav"
            else:
                suffix = "webm"

        stt = GeminiSpeechBackend()
        res = stt.transcribe(audio_bytes, format=suffix)
        transcription = (res.text if hasattr(res, "text") else str(res)).strip()
        if not transcription:
            return {"error": "Could not transcribe audio (no speech detected)."}

        chat_req = ChatRequest(message=transcription, tts=tts)
        chat_res = await chat_endpoint(chat_req)
        return {
            "transcription": transcription,
            "response": chat_res.get("response", ""),
            "tool_calls": chat_res.get("tool_calls", []),
        }
    except RuntimeError as exc:
        if "GEMINI_API_KEY" in str(exc) or "Gemini STT" in str(exc):
            return {
                "error": (
                    "Gemini API key is not configured or is the default placeholder. "
                    "On Android devices, speech recognition is handled directly on-device "
                    "via native Android SpeechRecognizer."
                )
            }
        logger.exception("Voice upload pipeline failed: %s", exc)
        return {"error": f"Voice upload error: {exc}"}
    except Exception as exc:
        logger.exception("Voice upload pipeline failed: %s", exc)
        return {"error": f"Voice upload error: {exc}"}


_voice_session: Dict[str, Any] = {
    "state": "STANDBY",
    "awake_until": 0.0,
}


@app.post("/api/voice/vad_utterance")
async def voice_vad_utterance_endpoint(request: Request):
    """Process a PCM WAV utterance from client continuous AudioRecord VAD.

    Transcribes audio using Google Chromium free STT (no API key required).
    Handles wake-word states (STANDBY vs AWAKE_WAITING_FOR_COMMAND) and executes commands.
    """
    global _voice_session

    content_type = request.headers.get("content-type", "")
    tts_requested = True
    if "multipart/form-data" in content_type:
        form = await request.form()
        audio_file = form.get("audio")
        if audio_file and hasattr(audio_file, "read"):
            audio_bytes = await audio_file.read()
        else:
            audio_bytes = b""
        tts_val = form.get("tts", "true")
        tts_requested = str(tts_val).lower() in ("true", "1", "yes")
    else:
        audio_bytes = await request.body()
        tts_val = request.query_params.get("tts", "true")
        tts_requested = str(tts_val).lower() in ("true", "1", "yes")

    if not audio_bytes or len(audio_bytes) < 44:
        return {"error": "Received empty or invalid WAV audio data", "status": "ignored"}

    def _do_transcribe(data: bytes) -> str:
        try:
            import io
            import speech_recognition as sr
            r = sr.Recognizer()
            with sr.AudioFile(io.BytesIO(data)) as source:
                audio = r.record(source)
            # Try English first
            try:
                txt = r.recognize_google(audio, language="en-US")
                if txt and txt.strip():
                    return txt.strip()
            except sr.UnknownValueError:
                pass
            except Exception as e:
                logger.debug("Google STT en-US error: %s", e)
            # Try Persian fallback
            try:
                txt = r.recognize_google(audio, language="fa-IR")
                if txt and txt.strip():
                    return txt.strip()
            except sr.UnknownValueError:
                pass
            except Exception as e:
                logger.debug("Google STT fa-IR error: %s", e)
        except Exception as exc:
            logger.warning("VAD transcription exception: %s", exc)
        return ""

    transcript = await asyncio.to_thread(_do_transcribe, audio_bytes)
    transcript = transcript.strip()
    if not transcript:
        return {
            "status": "no_speech",
            "transcription": "",
            "message": "No speech detected in audio utterance.",
        }

    logger.info("VAD Utterance transcribed: '%s' (Session state: %s)", transcript, _voice_session.get("state"))

    import re
    now = time.time()
    is_awake = (now < _voice_session.get("awake_until", 0.0))
    is_persian = any("\u0600" <= c <= "\u06FF" for c in transcript)

    # Extract device telemetry (if provided by native phone client)
    battery_param = request.query_params.get("battery") or request.headers.get("x-device-battery")
    charging_param = request.query_params.get("charging") or request.headers.get("x-device-charging")
    dev_battery = int(battery_param) if battery_param and battery_param.isdigit() else None
    dev_charging = str(charging_param).lower() in ("true", "1", "yes") if charging_param else None

    # Check sleep triggers in speech first before wake word check
    transcript_lower = transcript.lower()
    sleep_terms = (
        "stop listening", "bye jarvis", "goodbye jarvis", "go to sleep",
        "jarvis sleep", "sleep mode", "standby", "that's all jarvis", "mute yourself",
        "دیگه گوش نده", "گوش نده", "خداحافظ جارویس", "فعلا جارویس", "جارویس بخواب",
        "بخواب", "استندبای", "خداحافظ"
    )
    if any(s in transcript_lower for s in sleep_terms):
        _voice_session["state"] = "STANDBY"
        _voice_session["awake_until"] = 0.0
        reply = (
            "به حالت استندبای می‌روم قربان. هر زمان نیاز داشتید جارویس را صدا بزنید."
            if is_persian
            else "Going into standby mode, sir. Just say Jarvis whenever you need me."
        )
        return {
            "status": "sleep",
            "state": "STANDBY",
            "transcription": transcript,
            "response": reply,
            "action": "sleep",
            "client_actions": [{"type": "sleep"}],
            "fast_path": True,
            "tts": tts_requested,
        }

    # Wake patterns (including phonetic variants: javis, travis, service, etc.)
    wake_regex = re.compile(
        r"^(?:(?:hey|hi|ok|okay|dear)\s+)?(?:jarvis\s+jarvis|jarvis|javis|service|harvey|travis|چاویس|جارویس|جارویز)(?:[,\s]+(.*))?$",
        re.IGNORECASE,
    )
    m_wake = wake_regex.match(transcript)

    command_to_run = None
    if m_wake:
        remainder = (m_wake.group(1) or "").strip()
        if not remainder:
            # User said "Jarvis" alone!
            _voice_session["state"] = "AWAKE_WAITING_FOR_COMMAND"
            _voice_session["awake_until"] = now + 15.0  # 15 seconds window
            reply_wake = "در خدمت شما هستم قربان. گوش می‌دهم." if is_persian else "At your service, sir. I'm listening."
            return {
                "status": "awake",
                "state": "AWAKE_WAITING_FOR_COMMAND",
                "transcription": transcript,
                "response": reply_wake,
                "action": "wake_alone",
                "tts": tts_requested,
            }
        else:
            # User said "Jarvis, <command>"
            command_to_run = remainder
            _voice_session["state"] = "STANDBY"
            _voice_session["awake_until"] = 0.0
    elif is_awake:
        # Already awake waiting for command!
        command_to_run = transcript
        _voice_session["state"] = "STANDBY"
        _voice_session["awake_until"] = 0.0
    else:
        # In STANDBY and no wake word found
        return {
            "status": "ignored",
            "reason": "no_wake_word",
            "transcription": transcript,
            "state": "STANDBY",
        }

    # Execute the command with device telemetry
    chat_req = ChatRequest(
        message=command_to_run,
        tts=False,
        device_battery=dev_battery,
        device_charging=dev_charging,
    )
    chat_res = await chat_endpoint(chat_req)

    return {
        "status": "executed",
        "state": _voice_session["state"],
        "transcription": transcript,
        "command": command_to_run,
        "response": chat_res.get("response", ""),
        "tool_calls": chat_res.get("tool_calls", []),
        "client_actions": chat_res.get("client_actions", []),
        "fast_path": chat_res.get("fast_path", False),
        "tts": tts_requested,
    }


async def _synthesize_edge_tts(
    text: str,
    voice: Optional[str] = None,
    rate: Optional[str] = "+5%",
    pitch: Optional[str] = "-2Hz",
) -> Response:
    clean_text = _clean_tts_response(text)
    if not clean_text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    is_persian = any("\u0600" <= c <= "\u06FF" for c in clean_text)
    selected_voice = voice or ("fa-IR-FaridNeural" if is_persian else "en-GB-RyanNeural")

    try:
        import aiohttp
        import edge_tts

        connector = aiohttp.TCPConnector(resolver=aiohttp.ThreadedResolver())
        comm_kwargs = {
            "text": clean_text,
            "voice": selected_voice,
            "rate": rate or "+5%",
            "pitch": pitch or "-2Hz",
        }
        try:
            import inspect
            sig = inspect.signature(edge_tts.Communicate)
            if "connector" in sig.parameters:
                comm_kwargs["connector"] = connector
        except Exception:
            pass

        try:
            comm = edge_tts.Communicate(**comm_kwargs)
        except TypeError:
            comm_kwargs.pop("connector", None)
            comm = edge_tts.Communicate(**comm_kwargs)

        buffer = bytearray()
        async for chunk in comm.stream():
            if chunk.get("type") == "audio":
                buffer.extend(chunk.get("data", b""))

        if not buffer:
            raise RuntimeError("No audio data synthesized")

        return Response(content=bytes(buffer), media_type="audio/mpeg")
    except Exception as exc:
        logger.warning("edge-tts synthesis failed: %s", exc)
        raise HTTPException(status_code=503, detail=f"TTS synthesis unavailable: {exc}")


@app.post("/api/tts")
async def tts_endpoint(req: TTSRequest):
    if not req.text:
        return {"success": False, "error": "Text is required"}
    _speak_async(req.text)
    return {"success": True}


@app.post("/api/tts/audio")
async def tts_audio_endpoint(req: TTSAudioRequest):
    """Synthesize speech using edge-tts (British RyanNeural or Persian FaridNeural)."""
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    return await _synthesize_edge_tts(text, req.voice, req.rate, req.pitch)


@app.get("/api/tts/audio")
async def tts_audio_get_endpoint(
    text: str = Query(...),
    voice: Optional[str] = None,
    rate: Optional[str] = "+5%",
    pitch: Optional[str] = "-2Hz",
):
    """Stream speech audio directly for web audio playback."""
    text = (text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    return await _synthesize_edge_tts(text, voice, rate, pitch)


@app.get("/api/camera/latest")
async def get_latest_camera():
    """Serve the latest camera photo captured by android_camera_capture."""
    from openjarvis.tools.android_tools import get_cam_photo_path
    cam_file = get_cam_photo_path()
    if not cam_file.exists():
        raise HTTPException(status_code=404, detail="No camera photo captured yet")
    return FileResponse(cam_file, media_type="image/jpeg")


@app.post("/api/tools/{tool_name}")
async def direct_tool_endpoint(tool_name: str, params: Optional[Dict[str, Any]] = None):
    """Directly execute an Android tool (e.g. for quick action chips)."""
    import openjarvis.tools  # noqa: F401
    import openjarvis.tools.android_tools  # noqa: F401
    if not ToolRegistry.contains(tool_name):
        try:
            import importlib
            import openjarvis.tools.android_tools
            importlib.reload(openjarvis.tools.android_tools)
        except Exception:
            pass

    if not ToolRegistry.contains(tool_name):
        raise HTTPException(status_code=404, detail=f"Tool '{tool_name}' not found")

    params = params or {}
    tcls = ToolRegistry.get(tool_name)
    config = load_config()
    tool_instance = tcls(**file_tool_kwargs(tool_name, config)) if isinstance(tcls, type) else tcls

    try:
        res = tool_instance.execute(**params)
        client_actions = []
        meta = getattr(res, "metadata", {}) or {}
        if "client_action" in meta:
            client_actions.append({
                "type": meta["client_action"],
                "target": meta.get("target") or meta.get("package") or "",
                "package": meta.get("package", ""),
            })
        elif tool_name == "android_launch_app":
            client_actions.append({
                "type": "launch_app",
                "target": params.get("app") or params.get("app_name") or "",
            })

        return {
            "success": res.success,
            "content": res.content,
            "tool_name": tool_name,
            "client_actions": client_actions,
        }
    except Exception as exc:
        return {
            "success": False,
            "content": f"Execution failed: {exc}",
            "tool_name": tool_name,
            "client_actions": [],
        }


def run_server(host: str = "0.0.0.0", port: int = 8765):
    """Start uvicorn server bound to 0.0.0.0 for local and remote VPS access."""
    import uvicorn
    uvicorn.run(app, host=host, port=port, log_level="warning")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description="Open Jarvis Web Server")
    parser.add_argument("--host", default="0.0.0.0", help="Host to bind (0.0.0.0 for all interfaces)")
    parser.add_argument("--port", type=int, default=8765, help="Port to bind")
    args, _ = parser.parse_known_args()
    run_server(host=args.host, port=args.port)
