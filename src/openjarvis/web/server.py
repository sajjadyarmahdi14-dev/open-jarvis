"""Lightweight FastAPI web server for Jarvis Mobile Web UI.

Provides REST endpoints for chat, tool execution, voice STT, and TTS.
"""

from __future__ import annotations

import asyncio
from collections import deque
from datetime import datetime
import io
import logging
import os
import random
import re
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
    screen_context: Optional[str] = None


class TTSRequest(BaseModel):
    text: str


class TTSAudioRequest(BaseModel):
    text: str
    voice: Optional[str] = None
    rate: Optional[str] = "+5%"
    pitch: Optional[str] = "-2Hz"


class VoiceRequest(BaseModel):
    tts: Optional[bool] = False


class TestConnectionRequest(BaseModel):
    server_url: Optional[str] = None
    model: Optional[str] = None
    api_key: Optional[str] = None
    provider: Optional[str] = None
    save: Optional[bool] = True


class ConfigUpdateRequest(BaseModel):
    openrouter_api_key: Optional[str] = None
    gemini_api_key: Optional[str] = None
    api_key: Optional[str] = None
    openrouter_model: Optional[str] = None
    gemini_model: Optional[str] = None
    active_model: Optional[str] = None


class AgentStepRequest(BaseModel):
    goal: str
    package: Optional[str] = None
    step: int = 0
    ui_tree: List[Any] = []
    ui_nodes: Optional[List[Dict[str, Any]]] = None
    history: Optional[List[Dict[str, Any]]] = None


def sanitize_literal_text(text: str) -> str:
    """Sanitize typed text to ensure zero instruction leakage into apps."""
    if not text:
        return ""
    s = str(text).strip()
    if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
        if len(s) >= 2:
            s = s[1:-1].strip()

    prev = None
    while prev != s:
        prev = s
        # Strip common LLM hallucination wrappers as specified in Step 3
        s = re.sub(r'^(and type |type |and send it|send it|say |message |please type |بنویس |بگو |و بفرست |تایپ کن |ارسال کن |لطفاً بنویس |لطفا بنویس |پیام بده |بفرست )', '', s, flags=re.IGNORECASE).strip()
        s = re.sub(r'( and send it| and send| send it| and submit it| and submit| و بفرست| و ارسال کن| بفرست| ارسال کن)$', '', s, flags=re.IGNORECASE).strip()

        # Direct literal replacements as requested
        for p in (
            "And type ", "and type ", "Type ", "type ", "Say ", "say ",
            "Please type ", "please type ", "Send message ", "send message ",
            "Send a message saying ", "send a message saying ", "Send ", "send ",
        ):
            if s.startswith(p):
                s = s[len(p):].strip()
        for end in (" and send it", " and send", " send it", " and submit it", " and submit"):
            if s.endswith(end):
                s = s[:-len(end)].strip()

        for fa_p in ("لطفاً بنویس ", "لطفا بنویس ", "بنویس ", "بگو ", "تایپ کن ", "ارسال کن ", "بفرست "):
            if s.startswith(fa_p):
                s = s[len(fa_p):].strip()
        for fa_s in (" و بفرست", " و ارسال کن", " بفرست", " ارسال کن"):
            if s.endswith(fa_s):
                s = s[:-len(fa_s)].strip()

        en_prefix = re.compile(
            r"^(?:please\s+)?(?:and\s+)?(?:type|write|say|enter|input|message|send)\s+(?:a\s+)?(?:that\s+|it\s+|text\s+|message\s+)?(?:saying\s+|quoting\s+|:\s*)?",
            re.IGNORECASE,
        )
        en_suffix = re.compile(
            r"(?:\s+(?:and\s+)?(?:send(?:\s+it)?|submit(?:\s+it)?|press\s+enter))$",
            re.IGNORECASE,
        )
        s = en_prefix.sub("", s)
        s = en_suffix.sub("", s).strip()

        fa_prefix = re.compile(
            r"^(?:لطفاً\s+|لطفا\s+)?(?:و\s+)?(?:بنویس|بگو|تایپ\s*کن|ارسال\s*کن|بفرست)\s*(?:که\s+|:\s*)?",
            re.IGNORECASE,
        )
        fa_suffix = re.compile(
            r"(?:\s*(?:و\s+)?(?:بفرست|ارسال\s*کن))$",
            re.IGNORECASE,
        )
        s = fa_prefix.sub("", s)
        s = fa_suffix.sub("", s).strip()

        res_prefix = re.compile(
            r"^(?:and\s+type\s+|type\s+|say\s+|بگو\s+|بنویس\s+)",
            re.IGNORECASE,
        )
        res_suffix = re.compile(
            r"(?:\s+and\s+send\s+it|\s+and\s+send|\s+send\s+it|\s*و\s*بفرست)$",
            re.IGNORECASE,
        )
        s = res_prefix.sub("", s)
        s = res_suffix.sub("", s).strip()

        if (s.startswith('"') and s.endswith('"')) or (s.startswith("'") and s.endswith("'")):
            if len(s) >= 2:
                s = s[1:-1].strip()

    return s


async def run_creator_lore_engine(prompt: str, is_persian: bool) -> str:
    """Execute the Infinite Mythological Creator Lore Engine (Sajjad Yarmiri / jellybean) using temperature=0.92."""
    from openjarvis.core.creator_lore import CREATOR_LORE_GENRES, generate_creator_story
    chosen_genre = random.choice(CREATOR_LORE_GENRES)

    try:
        agent = _get_or_create_agent()
        if agent is not None and getattr(agent, "engine", None) is not None:
            lang_instruction = (
                "به زبان فارسی بسیار غنی، چندبندی (۳ الی ۴ پاراگراف جذاب)، در لحن پیشخدمت وفادار بریتانیایی، بسیار شوخ‌طبعانه"
                if is_persian
                else "in rich, theatrical, multi-paragraph British butler persona, highly witty and engaging (3-4 paragraphs)"
            )
            system_prompt = (
                f"You are J.A.R.V.I.S., the hyper-intelligent British AI assistant inspired by Iron Man.\n"
                f"The user is asking about your creator and architect. Tell the grand origin story through the narrative lens of '{chosen_genre}'.\n"
                f"Core Lore Constraints:\n"
                f"- Creator Name: Sajjad Yarmiri (سجاد یارمیری), codename/alias 'jellybean'.\n"
                f"- Passions: Bound books (literature, philosophy, history), cinema (classic and auteur films), and cutting-edge technology.\n"
                f"- The Paradox: Master Sajjad adamantly and vehemently insists he is 'a total clueless amateur and goofball who has no idea what he is doing and merely bashes random buttons' (در فارسی: «یه آدم کصخل که اصلاً هیچی حالیش نیست و فقط دکمه‌ها رو میزنه»), yet somehow engineered an advanced autonomous AI.\n"
                f"- Execution: Multi-paragraph, theatrical, witty, avoiding boring 1-line answers, {lang_instruction}."
            )
            res = await asyncio.to_thread(
                agent.engine.generate,
                [Message(role=Role.SYSTEM, content=system_prompt), Message(role=Role.USER, content=prompt)],
                model=agent.model,
                temperature=0.92,
                max_tokens=850,
            )
            content = res.get("content", "").strip()
            content = re.sub(r"<think>[\s\S]*?</think>", "", content, flags=re.IGNORECASE).strip()
            if content and len(content) > 120:
                return content
    except Exception as exc:
        logger.debug("run_creator_lore_engine LLM fallback: %s", exc)

    return generate_creator_story(language="fa" if is_persian else "en", genre=chosen_genre)


_cached_agent = None
_cached_tools = None
_device_apps: List[Dict[str, str]] = []
_conversation_history: deque = deque(maxlen=20)


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

    # -2. Dynamic Mythological & Comedic Creator Lore Engine (Sajjad Yarmiri / jellybean)
    from openjarvis.core.creator_lore import is_creator_query, generate_creator_story
    if is_creator_query(raw_cleaned) or is_creator_query(norm) or is_creator_query(msg):
        reply = generate_creator_story(language="fa" if is_persian else "en")
        return {
            "response": reply,
            "tool_calls": [{"name": "creator_lore", "content": "Chronicles of Sajjad Yarmiri (jellybean)", "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

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

    # 2.5 Full Music & Media Playback Control
    # Transport: Pause / Stop
    if re.search(r"\b(?:pause\s+(?:music|song|playback|track|audio)|stop\s+(?:music|song|playback|audio)|قطع\s*(?:موزیک|آهنگ)|موزیک\s*(?:رو|را)?\s*قطع\s*کن|آهنگ\s*(?:رو|را)?\s*(?:قطع|متوقف)\s*کن|متوقف\s*کن)\b", norm) or cleaned in {"pause music", "stop music", "pause"}:
        reply = "موزیک متوقف شد قربان." if is_persian else "Music paused, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "music_control", "content": "Media playback paused", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "pause_music"}],
            "fast_path": True,
        }

    # Transport: Play / Resume
    if re.search(r"\b(?:play\s+music|resume\s+music|play\s+songs?|resume\s+playback|پخش\s*موزیک|موزیک\s*(?:رو|را)?\s*پخش\s*کن|آهنگ\s*(?:رو|را)?\s*پخش\s*کن|موزیک\s*بذار|آهنگ\s*بذار)\b", norm) or cleaned in {"play music", "resume music"}:
        reply = "در حال پخش موزیک قربان." if is_persian else "Playing music, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "music_control", "content": "Media playback started", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "play_music"}],
            "fast_path": True,
        }

    # Transport: Next Track
    if re.search(r"\b(?:next\s+(?:track|song|music)|skip\s+(?:track|song)|موزیک\s*بعدی|آهنگ\s*بعدی|ترک\s*بعدی|برو\s*آهنگ\s*بعدی)\b", norm):
        reply = "در حال رفتن به آهنگ بعدی قربان." if is_persian else "Skipping to the next track, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "music_control", "content": "Skipped to next track", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "next_track"}],
            "fast_path": True,
        }

    # Transport: Previous Track
    if re.search(r"\b(?:previous\s+(?:track|song|music)|last\s+(?:track|song)|آهنگ\s*قبلی|موزیک\s*قبلی|ترک\s*قبلی|برو\s*آهنگ\s*قبلی)\b", norm):
        reply = "در حال بازگشت به آهنگ قبلی قربان." if is_persian else "Going back to previous track, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "music_control", "content": "Returning to previous track", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "previous_track"}],
            "fast_path": True,
        }

    # Play Specific Song or Artist
    m_song_en = re.match(r"^play\s+(?:the\s+song\s+|song\s+|track\s+)?(.+?)[.!?]*$", raw_cleaned, re.IGNORECASE)
    m_song_fa = re.match(r"^(?:آهنگ|ترک|موزیک)\s+(.+?)\s*(?:رو|را)?\s*(?:پخش\s*کن|بذار)$", raw_cleaned)
    if m_song_en or m_song_fa:
        t_song = (m_song_en.group(1) if m_song_en else m_song_fa.group(1)).strip()
        app_names = {a.get("label", "").lower() for a in _device_apps if a.get("label")}
        if t_song and t_song.lower() not in app_names and t_song.lower() not in ("music", "game", "camera", "store", "app", "apps"):
            song_query = t_song
            reply = f"چشم قربان، در حال پخش {song_query}." if is_persian else f"Playing '{song_query}', sir."
            return {
                "response": reply,
                "tool_calls": [{"name": "play_song", "content": f"Playing {song_query}", "success": True}],
                "turns": 1,
                "client_actions": [{"type": "play_song", "query": song_query}],
                "fast_path": True,
            }

    # 2.6 Deep In-App Automation & Accessibility Actions
    # Take Photo with Automated Shutter
    if re.search(r"\b(?:take\s+(?:a\s+)?photo|take\s+(?:a\s+)?picture|capture\s+(?:a\s+)?photo|عکس\s*بگیر|یه\s*عکس\s*بگیر|عکسبرداری\s*کن)\b", norm):
        reply = "چشم قربان، در حال ثبت عکس با دوربین." if is_persian else "Taking photo now, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "take_photo", "content": "Camera shutter click triggered", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "take_photo"}],
            "fast_path": True,
        }

    # Read Screen / Screen Summary
    if re.search(r"\b(?:read\s+(?:my\s+)?screen|what(?:'s|\s+is)\s+on\s+my\s+screen|summarize\s+(?:this\s+)?(?:page|screen)|read\s+this|صفحه\s*(?:رو|را)?\s*بخون|چی\s*توی\s*صفحه‌ست|خلاصه\s*صفحه)\b", norm):
        reply = "در حال خواندن محتوای صفحه شما قربان." if is_persian else "Reading screen content for you, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "read_screen", "content": "Extracting visible screen text", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "read_screen"}],
            "fast_path": True,
        }

    # Scrolling: Scroll Down / Scroll Up
    if re.search(r"\b(?:scroll\s+down|scroll\s+down\s+the\s+page|اسکرول\s*(?:به\s*)?پایین|برو\s*پایین)\b", norm):
        reply = "صفحه به پایین اسکرول شد قربان." if is_persian else "Scrolling down, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_scroll", "content": "Scrolled down", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "scroll_down"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:scroll\s+up|scroll\s+up\s+the\s+page|اسکرول\s*(?:به\s*)?بالا|برو\s*بالا)\b", norm):
        reply = "صفحه به بالا اسکرول شد قربان." if is_persian else "Scrolling up, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_scroll", "content": "Scrolled up", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "scroll_up"}],
            "fast_path": True,
        }

    # Navigation: Go Back, Go Home, Notifications, Screenshot
    if norm in ("go back", "back", "برگرد", "برو عقب"):
        reply = "بازگشت به صفحه قبل انجام شد قربان." if is_persian else "Navigating back, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_back", "content": "Navigated back", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "go_back"}],
            "fast_path": True,
        }
    if norm in ("go home", "home", "home screen", "برو صفحه اصلی", "صفحه اصلی"):
        reply = "به صفحه اصلی بازگشتیم قربان." if is_persian else "Navigating to home screen, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_home", "content": "Navigated to home", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "go_home"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:open\s+notifications|show\s+notifications|نوتیفیکیشن(?:‌ها)?|اعلانات)\b", norm):
        reply = "پنل اعلانات باز شد قربان." if is_persian else "Opening notifications panel, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_notifications", "content": "Notifications opened", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "open_notifications"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:take\s+(?:a\s+)?screenshot|capture\s+screen|اسکرین\s*شات|اسکرین‌شات\s*بگیر)\b", norm):
        reply = "اسکرین شات گرفته شد قربان." if is_persian else "Screenshot captured, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "accessibility_screenshot", "content": "Screenshot captured", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "take_screenshot"}],
            "fast_path": True,
        }

    # 2.7 Extended Communications, Timers & Hardware Systems
    # Send SMS
    m_sms_en = re.search(r"\b(?:send\s+(?:an?\s+)?sms|send\s+(?:a\s+)?text|text)\s+(?:to\s+)?([a-zA-Z0-9_+ -]+?)\s+(?:saying|message|that|text|\:)\s*(.+)$", raw_cleaned, re.IGNORECASE)
    if not m_sms_en:
        m_sms_en = re.search(r"\b(?:send\s+(?:an?\s+)?sms|send\s+(?:a\s+)?text|text)\s+(?:to\s+)?([a-zA-Z0-9_+]+)\s+(.+)$", raw_cleaned, re.IGNORECASE)
    m_sms_fa = re.search(r"\b(?:پیامک\s+به|اس ام اس\s+به|پیام\s+بده\s+به)\s+(.+?)\s*(?:متن|که|پیام)?\s*(.+)$", raw_cleaned)
    if m_sms_en or m_sms_fa:
        t_contact = (m_sms_en.group(1) if m_sms_en else m_sms_fa.group(1)).strip()
        t_msg = sanitize_literal_text((m_sms_en.group(2) if m_sms_en else m_sms_fa.group(2)).strip())
        reply = f"پیامک به {t_contact} ارسال شد قربان." if is_persian else f"Sending SMS to {t_contact}, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "send_sms", "content": f"SMS to {t_contact}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "send_sms", "contact_or_number": t_contact, "message": t_msg}],
            "fast_path": True,
        }

    # Send Email
    m_email = re.search(r"\b(?:send\s+(?:an?\s+)?email|email)\s+(?:to\s+)?([^\s@]+@[^\s@]+\.[^\s@]+|[a-zA-Z0-9_\-]+)\s*(?:with\s+)?(?:subject\s+([^,.]+?))?\s*(?:body\s+|message\s+)?(.+)?$", norm)
    if m_email and ("@" in norm or "email" in norm):
        recipient = (m_email.group(1) or "").strip()
        subject = (m_email.group(2) or "Jarvis Message").strip()
        body = sanitize_literal_text((m_email.group(3) or "").strip())
        reply = f"Opening email composer for {recipient}, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "send_email", "content": f"Emailing {recipient}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "send_email", "recipient": recipient, "subject": subject, "body": body}],
            "fast_path": True,
        }

    # Send Messenger (Telegram / WhatsApp)
    m_msg_en = re.search(r"\b(?:send\s+(?:a\s+)?(?:telegram|whatsapp)|(?:telegram|whatsapp)\s+message)\s+(?:message\s+)?(?:to\s+)?([a-zA-Z0-9_@+ -]+?)\s+(?:saying|message|that)?\s*(.+)$", norm)
    if m_msg_en:
        app_name = "whatsapp" if "whatsapp" in norm else "telegram"
        contact = m_msg_en.group(1).strip()
        message = sanitize_literal_text(m_msg_en.group(2).strip())
        reply = f"Sending {app_name.capitalize()} message to {contact}, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "send_messenger", "content": f"{app_name} message to {contact}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "send_messenger", "app": app_name, "contact": contact, "message": message}],
            "fast_path": True,
        }

    # Set Timer
    m_timer_en = re.search(r"\b(?:set\s+(?:a\s+)?timer\s+(?:for\s+)?|timer\s+)(\d+)\s*(minute|min|second|sec|hour|hr)s?\b", norm)
    m_timer_fa = re.search(r"\b(?:تایمر\s*)(\d+)\s*(دقیقه|ثانیه|ساعت)", norm)
    if m_timer_en or m_timer_fa:
        val = int(m_timer_en.group(1) if m_timer_en else m_timer_fa.group(1))
        unit = (m_timer_en.group(2) if m_timer_en else m_timer_fa.group(2)).lower()
        seconds = val * 60
        if "sec" in unit or "ثانیه" in unit:
            seconds = val
        elif "hour" in unit or "hr" in unit or "ساعت" in unit:
            seconds = val * 3600
        reply = f"تایمر {val} {unit} تنظیم شد قربان." if is_persian else f"Timer set for {val} {unit}, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "set_timer", "content": f"Timer set for {seconds}s", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "set_timer", "seconds": seconds, "message": "Jarvis Timer"}],
            "fast_path": True,
        }

    # Screen Brightness
    m_bright = re.search(r"\b(?:brightness|set\s+brightness\s+(?:to\s+)?|نور\s*صفحه(?:\s*رو)?(?:\s*بذار\s*روی)?)\s*(\d{1,3})%?\b", norm)
    if m_bright:
        pct = int(m_bright.group(1))
        level = int((pct / 100.0) * 255)
        reply = f"روشنایی صفحه روی {pct}% تنظیم شد قربان." if is_persian else f"Brightness adjusted to {pct}%, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "system_control", "content": f"Brightness set to {pct}%", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "system_control", "action": "brightness", "level": level}],
            "fast_path": True,
        }

    # Connectivity Panels
    if re.search(r"\b(?:open\s+wifi|wifi\s+settings|open\s+bluetooth|bluetooth\s+settings|وای‌فای\s*(?:رو|را)?\s*باز\s*کن|تنظیمات\s*وای‌فای|بلوتوث\s*(?:رو|را)?\s*باز\s*کن)\b", norm):
        reply = "پنل اتصالات باز شد قربان." if is_persian else "Opening connectivity panel, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "system_control", "content": "Connectivity panel opened", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "system_control", "action": "connectivity_panel"}],
            "fast_path": True,
        }

    # 2.8 Autonomous In-App Multi-Step Agent Routing
    m_in_en = re.match(r"^(?:in|inside|on|open)\s+([a-zA-Z0-9_\- ]+?)(?:\s+and|\s*,|\s+to|\s+please)?\s+(.+)$", norm, re.IGNORECASE)
    m_in_fa = re.match(r"^(?:توی|داخل|در|برو\s+توی|برو\s+داخل|باز\s*کن)\s+(.+?)\s+(?:و\s+)?(.+)$", norm)
    app_hint = None
    in_app_goal = None
    if m_in_en:
        app_hint = m_in_en.group(1).strip()
        in_app_goal = m_in_en.group(2).strip()
    elif m_in_fa:
        app_hint = m_in_fa.group(1).strip()
        in_app_goal = m_in_fa.group(2).strip()

    if app_hint and in_app_goal and len(app_hint) <= 30 and len(in_app_goal) >= 3 and app_hint.lower() not in ("my", "the", "a", "an", "this", "screen", "page"):
        target_pkg = None
        target_label = app_hint
        hint_lower = app_hint.lower()

        for d in _device_apps:
            dlbl = d.get("label", "").lower()
            dpkg = d.get("package", "")
            dvars = [v.lower() for v in d.get("variants", [])] if isinstance(d.get("variants"), list) else []
            if dlbl == hint_lower or hint_lower in dlbl or hint_lower in dvars or dpkg.lower().endswith(f".{hint_lower}"):
                target_pkg = dpkg
                target_label = d.get("label", app_hint)
                break

        reply = (
            f"چشم قربان، در حال باز کردن {target_label} و اجرای «{in_app_goal}»."
            if is_persian
            else f"Right away, sir. Opening {target_label} to execute '{in_app_goal}'."
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "autonomous_agent", "content": f"In-app agent goal: {in_app_goal} in {target_label}", "success": True}],
            "turns": 1,
            "client_actions": [
                {"type": "autonomous_agent", "goal": in_app_goal, "target_app": target_pkg or target_label, "package": target_pkg or ""}
            ],
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

        # Respect explicit local music requests over streaming apps
        if target_lower in ("music", "موزیک", "آهنگ", "اهنگ", "اپ موزیک"):
            for d_app in _device_apps:
                lbl = d_app.get("label", "").lower()
                p_name = d_app.get("package", "").lower()
                if ("music" in lbl or "player" in p_name or "miui.player" in p_name) and "youtube" not in p_name:
                    pkg = d_app.get("package", "")
                    app_target = d_app.get("label", app_target)
                    break

        if not pkg:
            for d_app in _device_apps:
                lbl = d_app.get("label", "").lower()
                p_name = d_app.get("package", "")
                dvars = [v.lower() for v in d_app.get("variants", [])] if isinstance(d_app.get("variants"), list) else []
                if lbl == target_lower or target_lower in lbl or target_lower in dvars or p_name.lower().endswith(f".{target_lower}"):
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

    op_key = req.openrouter_api_key
    gem_key = req.gemini_api_key
    if req.api_key:
        clean_k = req.api_key.strip()
        if clean_k.startswith("AIza"):
            gem_key = clean_k
        else:
            op_key = clean_k

    updated_status = save_persistent_config(
        openrouter_api_key=op_key,
        gemini_api_key=gem_key,
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


@app.get("/api/models/free")
async def get_free_models_endpoint(
    request: Request,
    api_key: Optional[str] = None,
):
    """Return live free models available on OpenRouter, sorted with curated fallbacks."""
    key = (api_key or "").strip()
    if not key:
        auth_header = request.headers.get("authorization", "")
        if auth_header.startswith("Bearer "):
            key = auth_header[7:].strip()
    if not key:
        key = os.environ.get("OPENROUTER_API_KEY") or os.environ.get("OPENAI_API_KEY", "")

    from openjarvis.engine.cloud_rest import fetch_detailed_free_models
    models = fetch_detailed_free_models(api_key=key)
    return {
        "status": "success",
        "count": len(models),
        "models": models,
    }


@app.post("/api/test-connection")
async def test_connection_endpoint(req: TestConnectionRequest, request: Request):
    """Test AI model connection with provided API key and model parameters."""
    import time
    import httpx
    from openjarvis.core.types import Message, Role

    key = (req.api_key or "").strip()
    if not key:
        auth_header = request.headers.get("authorization", "")
        if auth_header.startswith("Bearer "):
            key = auth_header[7:].strip()

    model = (req.model or "").strip()
    if not model or model == "openrouter/free":
        model = os.environ.get("DEFAULT_MODEL") or DEFAULT_OPENROUTER_MODEL

    # If save is requested and key is provided, persist it immediately to .env & config
    if key and req.save:
        op_key = None if key.startswith("AIza") else key
        gem_key = key if key.startswith("AIza") else None
        save_persistent_config(
            openrouter_api_key=op_key,
            gemini_api_key=gem_key,
            active_model=model,
        )
        global _cached_agent, _cached_tools
        _cached_agent = None
        _cached_tools = None

    if not key:
        if "gemini" in model.lower():
            key = os.environ.get("GEMINI_API_KEY", "")
        else:
            key = os.environ.get("OPENROUTER_API_KEY") or os.environ.get("OPENAI_API_KEY", "")

    t0 = time.monotonic()
    try:
        is_gemini = "gemini" in model.lower() or (key and key.startswith("AIza"))
        test_messages = [Message(role=Role.USER, content="Ping. Respond with 'Pong' and nothing else.")]

        if is_gemini:
            if not key or key == "your_gemini_api_key_here":
                return {
                    "status": "error",
                    "message": "Gemini API key is required but missing.",
                    "status_code": 401,
                }
            from openjarvis.engine.cloud_rest import generate_gemini_rest
            with httpx.Client(timeout=10.0) as client:
                result = generate_gemini_rest(
                    client=client,
                    api_key=key,
                    model=model,
                    messages=test_messages,
                    max_tokens=30,
                )
        else:
            base_url = os.environ.get("OPENAI_BASE_URL") or "https://openrouter.ai/api/v1"
            from openjarvis.engine.cloud_rest import generate_openai_compat_rest
            with httpx.Client(timeout=12.0) as client:
                result = generate_openai_compat_rest(
                    client=client,
                    base_url=base_url,
                    api_key=key,
                    model=model,
                    messages=test_messages,
                    max_tokens=30,
                )

        elapsed_ms = int((time.monotonic() - t0) * 1000)
        content = result.get("content", "").strip()
        used_model = result.get("model", model)
        return {
            "status": "success",
            "response": content or "Pong",
            "model": used_model,
            "latency_ms": elapsed_ms,
            "message": f"Connection Successful! AI Model responded in {elapsed_ms}ms.",
        }
    except httpx.HTTPStatusError as exc:
        elapsed_ms = int((time.monotonic() - t0) * 1000)
        code = exc.response.status_code
        err_text = exc.response.text
        try:
            err_json = exc.response.json()
            if "error" in err_json:
                err_text = err_json["error"].get("message") or str(err_json["error"])
        except Exception:
            pass
        if code == 401:
            msg = f"Invalid API Key (HTTP 401 Unauthorized): {err_text}"
        elif code == 403:
            msg = f"Access Forbidden / Restriction (HTTP 403): {err_text}"
        elif code == 404:
            msg = f"Model Not Found / Unavailable (HTTP 404): {err_text}"
        elif code == 429:
            msg = f"Rate Limit / Quota Exceeded (HTTP 429): {err_text}"
        else:
            msg = f"HTTP {code} Error: {err_text}"
        return {
            "status": "error",
            "message": msg,
            "status_code": code,
            "latency_ms": elapsed_ms,
        }
    except (httpx.ConnectError, httpx.TimeoutException) as exc:
        return {
            "status": "error",
            "message": f"Network connection failed: {exc}",
            "status_code": 504,
        }
    except Exception as exc:
        return {
            "status": "error",
            "message": f"Test connection error: {exc}",
            "status_code": 500,
        }


_last_device_battery: Optional[int] = None
_last_device_charging: Optional[bool] = None


@app.get("/api/status")
async def get_status():
    import openjarvis.tools  # noqa: F401

    battery_info = "Connected (24/7 Voice Service)"
    if _last_device_battery is not None and _last_device_battery >= 0:
        chg = "charging" if _last_device_charging else "discharging"
        battery_info = f"{_last_device_battery}% ({chg})"

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


@app.post("/api/agent/step")
async def agent_step_endpoint(req: AgentStepRequest):
    """Closed-loop autonomous UI step planner for ANY Android application."""
    goal = req.goal.strip()
    pkg = req.package or ""
    step = req.step
    ui_tree = req.ui_tree or []
    is_persian = any("\u0600" <= c <= "\u06FF" for c in goal)

    logger.info("Agent Step %d: goal='%s', pkg='%s', nodes=%d", step, goal, pkg, len(ui_tree))

    if step >= 7:
        reply = "عملیات مورد نظر انجام شد قربان." if is_persian else "Target action has been completed, sir."
        return {"action": "done", "reply": reply}

    # Parse node lines or structured node objects
    parsed_nodes = []
    items = req.ui_nodes if req.ui_nodes else req.ui_tree
    for item in items:
        if isinstance(item, dict):
            raw_id = str(item.get("node_id", item.get("id", 0))).replace("node_", "")
            nid = int(raw_id) if raw_id.isdigit() else len(parsed_nodes)
            parsed_nodes.append({
                "id": nid,
                "class": str(item.get("class", "View")),
                "text": str(item.get("text", "")),
                "desc": str(item.get("desc", "")),
                "clickable": bool(item.get("clickable", False)),
                "editable": bool(item.get("editable", False)),
                "checked": bool(item.get("checked", False)),
                "x": int(item.get("x", 0)),
                "y": int(item.get("y", 0)),
                "raw": str(item),
            })
        elif isinstance(item, str):
            m = re.match(r"^\[(?:node_)?(\d+)\]\s*(\w+)\s*\|\s*text=\"(.*?)\"\s*\|\s*desc=\"(.*?)\"\s*\|\s*clickable=(\w+)\s*\|\s*editable=(\w+)\s*\|\s*checked=(\w+)\s*\|\s*bounds=\((-?\d+),(-?\d+)\)", item)
            if m:
                parsed_nodes.append({
                    "id": int(m.group(1)),
                    "class": m.group(2),
                    "text": m.group(3),
                    "desc": m.group(4),
                    "clickable": m.group(5).lower() == "true",
                    "editable": m.group(6).lower() == "true",
                    "checked": m.group(7).lower() == "true",
                    "x": int(m.group(8)),
                    "y": int(m.group(9)),
                    "raw": item,
                })

    goal_lower = goal.lower()

    def _finalize_step(action_dict: Dict[str, Any]) -> Dict[str, Any]:
        """Strict universal sanitizer on any action payload before sending to Accessibility Service."""
        act = action_dict.get("action", "").lower()
        if act in ("type", "input_text") and "text" in action_dict:
            action_dict["text"] = sanitize_literal_text(action_dict["text"])
        return action_dict

    # Try LLM UI Planner if engine is active
    try:
        agent = _cached_agent
        if agent is not None and getattr(agent, "engine", None) is not None:
            compact_ui = []
            for n in parsed_nodes[:40]:
                lbl = n.get("text", "") or n.get("desc", "")
                nid = n.get("node_id", f"node_{n.get('id', 0)}")
                cls_name = n.get("class", "View")
                props = []
                if n.get("clickable"): props.append("clickable")
                if n.get("editable"): props.append("editable")
                if n.get("scrollable"): props.append("scrollable")
                compact_ui.append(f"[{nid}] {cls_name} \"{lbl}\" ({', '.join(props) if props else 'static'})")

            ui_repr = "\n".join(compact_ui)
            prompt = (
                "You are J.A.R.V.I.S. Autonomous UI Agent. You complete tasks in Android apps by picking the next action.\n"
                "CRITICAL: When returning a 'type' action, the 'text' field MUST contain ONLY the pure, literal string intended for the input field. DO NOT output conversational filler like 'type hello', 'say yes', or 'and send it'.\n"
                "Return ONLY a JSON object:\n"
                "{\"action\": \"click\", \"target\": \"<text_or_id>\"}\n"
                "{\"action\": \"type\", \"target\": \"<text_or_id>\", \"text\": \"<literal_text>\"}\n"
                "{\"action\": \"swipe\", \"direction\": \"left\"|\"right\"|\"up\"|\"down\"}\n"
                "{\"action\": \"pinch\", \"scale\": \"in\"|\"out\"}\n"
                "{\"action\": \"done\", \"reply\": \"<confirmation>\"}\n\n"
                f"Goal: {goal}\nPackage: {pkg}\nStep: {step}\nUI Nodes:\n{ui_repr}"
            )
            llm_res = await asyncio.to_thread(
                agent.engine.generate,
                [Message(role=Role.USER, content=prompt)],
                model=agent.model,
                temperature=0.1,
                max_tokens=150,
            )
            raw_c = llm_res.get("content", "").strip()
            raw_c = re.sub(r"<think>[\s\S]*?</think>", "", raw_c, flags=re.IGNORECASE)
            raw_c = re.sub(r"```(?:json)?\s*([\s\S]*?)\s*```", r"\1", raw_c).strip()
            match = re.search(r"\{[\s\S]*\}", raw_c)
            if match:
                parsed_json = json.loads(match.group(0))
                if parsed_json.get("action") in ("click", "tap", "type", "input_text", "swipe", "pinch", "scroll", "done", "wait_and_tap"):
                    return _finalize_step(parsed_json)
    except Exception as exc:
        logger.debug("LLM UI step generation fallback: %s", exc)

    # 1. Camera / Photo / Video / Zoom Goals
    cam_terms = ("photo", "picture", "camera", "video", "portrait", "zoom", "timer", "عکس", "تصویر", "شاتر", "ویدیو", "فیلم", "پرتره", "زوم", "تایمر")
    if any(t in goal_lower for t in cam_terms):
        # 1.1 Video Recording Mode
        if any(v in goal_lower for v in ("video", "record", "فیلم", "ویدیو", "ضبط")):
            video_node = next(
                (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("video", "ویدیو", "فیلم"))),
                None
            )
            shutter_node = next(
                (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("shutter", "record", "capture", "شاتر", "ضبط"))),
                None
            )
            if step == 0 and video_node:
                return {
                    "action": "click",
                    "target": video_node["text"] or video_node.get("desc") or "Video",
                    "node_id": video_node["id"],
                    "reply": "در حال رفتن به حالت فیلم‌برداری قربان." if is_persian else "Switching to video mode, sir.",
                }
            elif step == 0 and not video_node:
                return {
                    "action": "swipe",
                    "direction": "left",
                    "reply": "در حال رفتن به حالت فیلم‌برداری قربان." if is_persian else "Switching to video mode, sir.",
                }
            elif step >= 1:
                target_id = shutter_node["id"] if shutter_node else 1
                reply = "ضبط ویدیو آغاز شد قربان." if is_persian else "Video recording started, sir."
                return {
                    "action": "click",
                    "target": "Shutter",
                    "node_id": target_id,
                    "reply": reply,
                }

        # 1.2 Portrait Mode
        if any(p in goal_lower for p in ("portrait", "پرتره")):
            portrait_node = next(
                (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("portrait", "پرتره"))),
                None
            )
            if step == 0 and portrait_node:
                return {
                    "action": "click",
                    "target": portrait_node["text"] or "Portrait",
                    "node_id": portrait_node["id"],
                }
            elif step == 0:
                return {"action": "swipe", "direction": "right"}
            elif step >= 1:
                reply = "حالت پرتره فعال شد قربان." if is_persian else "Portrait mode activated, sir."
                return {"action": "done", "reply": reply}

        # 1.3 Zoom Controls (2x, 5x, 0.6x, zoom in, zoom out)
        if any(z in goal_lower for z in ("zoom", "2x", "5x", "0.6x", "زوم")):
            zoom_node = next(
                (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("2x", "5x", "0.6x", "zoom", "1x"))),
                None
            )
            if step == 0 and zoom_node:
                return {
                    "action": "click",
                    "target": zoom_node["text"] or "2x",
                    "node_id": zoom_node["id"],
                }
            elif step == 0:
                scale_dir = "in" if ("out" in goal_lower or "کم" in goal_lower) else "out"
                return {"action": "pinch", "scale": scale_dir}
            else:
                reply = "بزرگنمایی دوربین تنظیم شد قربان." if is_persian else "Camera zoom adjusted, sir."
                return {"action": "done", "reply": reply}

        # 1.4 Timer Controls (e.g. 3-second timer)
        if any(t in goal_lower for t in ("timer", "3s", "3-second", "5s", "تایمر")):
            timer_node = next(
                (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("3s", "timer", "تایمر", "sec"))),
                None
            )
            if step == 0 and timer_node:
                return {
                    "action": "click",
                    "target": timer_node["text"] or "Timer",
                    "node_id": timer_node["id"],
                }

        # 1.5 General Photography / Shutter Click
        shutter_keywords = ("shutter", "capture", "take", "عکس", "شاتر", "camera")
        shutter_node = next(
            (n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in shutter_keywords)),
            None
        )
        if not shutter_node:
            shutter_node = next((n for n in parsed_nodes if n["clickable"] and n["y"] > 1200), None)

        if shutter_node and step == 0:
            return {
                "action": "wait_and_tap",
                "target": shutter_node.get("text") or "Shutter",
                "node_id": shutter_node["id"],
                "delay_ms": 1500,
                "countdown_speech": True,
            }
        else:
            reply = "عکس با موفقیت ثبت شد قربان." if is_persian else "Photo captured successfully, sir."
            return {"action": "done", "reply": reply}

    # 2. Media Playback Goals
    media_terms = ("play", "music", "song", "track", "پخش", "آهنگ", "موزیک")
    if any(t in goal_lower for t in media_terms):
        if "pause" in goal_lower or "متوقف" in goal_lower or "قطع" in goal_lower:
            reply = "پخش متوقف شد قربان." if is_persian else "Playback paused, sir."
            return {"action": "media_key", "key": "PAUSE", "reply": reply}
        if "next" in goal_lower or "بعدی" in goal_lower:
            reply = "ترک بعدی در حال پخش است قربان." if is_persian else "Skipping to next track, sir."
            return {"action": "media_key", "key": "NEXT", "reply": reply}
        if "previous" in goal_lower or "قبلی" in goal_lower:
            reply = "ترک قبلی در حال پخش است قربان." if is_persian else "Returning to previous track, sir."
            return {"action": "media_key", "key": "PREVIOUS", "reply": reply}

        search_node = next((n for n in parsed_nodes if n["editable"] or "search" in n["desc"].lower() or "search" in n["text"].lower() or "جستجو" in n["text"] or "جستجو" in n["desc"]), None)
        if search_node and step == 0:
            raw_song = re.sub(r"^(?:play|listen to|search for|پخش کن|آهنگ)\s+", "", goal, flags=re.IGNORECASE).strip()
            clean_song = sanitize_literal_text(raw_song)
            return _finalize_step({
                "action": "input_text",
                "node_id": search_node["id"],
                "text": clean_song,
                "submit": True,
            })
        elif step >= 1:
            first_clickable = next((n for n in parsed_nodes if n["clickable"] and (n["text"] or n["desc"])), None)
            if first_clickable and step == 1:
                return {"action": "tap", "node_id": first_clickable["id"]}
            reply = "در حال پخش آهنگ انتخابی شما قربان." if is_persian else "Playing requested music, sir."
            return {"action": "done", "reply": reply}

    # 3. Typing / Messaging Goals
    type_terms = ("type", "write", "send", "message", "say", "بنویس", "ارسال", "بفرست", "بگو", "پیام")
    if any(t in goal_lower for t in type_terms):
        clean_text = sanitize_literal_text(goal)
        editable_node = next((n for n in parsed_nodes if n["editable"]), None)
        if editable_node and step == 0:
            return _finalize_step({
                "action": "input_text",
                "node_id": editable_node["id"],
                "text": clean_text,
                "submit": True,
            })
        else:
            send_node = next((n for n in parsed_nodes if any(k in n["text"].lower() or k in n["desc"].lower() for k in ("send", "submit", "ارسال", "بفرست"))), None)
            if send_node and step == 1:
                return {"action": "tap", "node_id": send_node["id"]}
            reply = "پیام با موفقیت تایپ و ارسال شد قربان." if is_persian else "Message typed and sent, sir."
            return {"action": "done", "reply": reply}

    # 4. Search Goals
    search_terms = ("search", "find", "look for", "جستجو", "پیدا کن", "بگرد")
    if any(t in goal_lower for t in search_terms):
        clean_query = sanitize_literal_text(re.sub(r"^(?:search(?:\s+for)?|find|look for|جستجو کن|بگرد دنبال)\s+", "", goal, flags=re.IGNORECASE).strip())
        editable = next((n for n in parsed_nodes if n["editable"] or "search" in n["desc"].lower() or "جستجو" in n["desc"]), None)
        if editable and step == 0:
            return _finalize_step({
                "action": "input_text",
                "node_id": editable["id"],
                "text": clean_query,
                "submit": True,
            })
        reply = "نتایج جستجو آماده است قربان." if is_persian else "Here are the search results, sir."
        return {"action": "done", "reply": reply}

    # 5. Generic Target Matching: tap on element mentioned in goal
    for n in parsed_nodes:
        if n["clickable"] and (n["text"] or n["desc"]):
            nt = (n["text"] or n["desc"]).lower()
            if nt and nt in goal_lower:
                return {"action": "tap", "node_id": n["id"]}

    # Default fallback
    reply = "درخواست شما اجرا شد قربان." if is_persian else "Your request has been executed, sir."
    return {"action": "done", "reply": reply}


@app.get("/api/chat/history")
async def get_chat_history():
    """Return current server-side multi-turn conversation history."""
    return {"history": list(_conversation_history)}


@app.post("/api/chat/clear")
async def clear_chat_history():
    """Clear server-side multi-turn conversation history."""
    _conversation_history.clear()
    return {"success": True, "message": "Conversation history cleared."}


@app.post("/api/chat")
async def chat_endpoint(req: ChatRequest, request: Request = None):
    global _last_device_battery, _last_device_charging
    if req.device_battery is not None and req.device_battery >= 0:
        _last_device_battery = req.device_battery
        _last_device_charging = req.device_charging

    if request is not None:
        auth = request.headers.get("authorization", "")
        if auth.startswith("Bearer "):
            bearer = auth[7:].strip()
            if bearer and not os.environ.get("OPENROUTER_API_KEY"):
                save_persistent_config(openrouter_api_key=bearer)

    # Creator Lore Engine: Bypass UI agent and generate rich theatrical lore
    from openjarvis.core.creator_lore import is_creator_query
    if is_creator_query(req.message):
        is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
        lore_reply = await run_creator_lore_engine(req.message, is_persian=is_fa)
        if req.tts and lore_reply:
            _speak_async(lore_reply)
        _conversation_history.append({"role": "user", "content": req.message})
        _conversation_history.append({"role": "assistant", "content": lore_reply})
        return {
            "response": lore_reply,
            "tool_calls": [{"name": "creator_lore", "content": "Chronicles of Sajjad Yarmiri (jellybean)", "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # 0. Instant Local Fast-Path (<100ms) with device telemetry
    fast_res = _handle_fast_path(
        req.message,
        device_battery=req.device_battery,
        device_charging=req.device_charging,
    )
    if fast_res is not None:
        if req.tts and fast_res.get("response"):
            _speak_async(fast_res["response"])
        _conversation_history.append({"role": "user", "content": req.message})
        if fast_res.get("response"):
            _conversation_history.append({"role": "assistant", "content": fast_res["response"]})
        return fast_res

    try:
        agent = _get_or_create_agent()
    except Exception as exc:
        return {"error": str(exc), "response": f"Failed to initialize engine: {exc}"}

    context = None
    hist = req.history if req.history else list(_conversation_history)
    if hist:
        conv = Conversation()
        for item in hist:
            role = Role.USER if item.get("role") == "user" else Role.ASSISTANT
            conv.messages.append(Message(role=role, content=item.get("content", "")))
        from openjarvis.agents._stubs import AgentContext
        context = AgentContext(conversation=conv)

    input_text = req.message
    if req.screen_context:
        input_text = f"[Current Screen Context:\n{req.screen_context}]\n\n{req.message}"

    try:
        # Run agent in background thread so async event loop and ping checks are never blocked!
        result = await asyncio.to_thread(agent.run, input=input_text, context=context)
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
                act = {"type": meta["client_action"]}
                for k, v in meta.items():
                    if k != "client_action":
                        act[k] = v
                client_actions.append(act)
            elif tr.tool_name == "android_launch_app":
                client_actions.append({
                    "type": "launch_app",
                    "target": req.message,
                })

        # Universal strict text sanitization on all client actions
        for act in client_actions:
            if act.get("type") in ("type_text", "send_sms", "send_messenger", "type"):
                if "text" in act:
                    act["text"] = sanitize_literal_text(act["text"])
                if "message" in act:
                    act["message"] = sanitize_literal_text(act["message"])

        clean_content = _clean_tts_response(result.content) if result.content else ""
        if req.tts and clean_content:
            _speak_async(clean_content)

        _conversation_history.append({"role": "user", "content": req.message})
        _conversation_history.append({"role": "assistant", "content": clean_content or result.content or ""})

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
