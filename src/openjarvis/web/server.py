"""Lightweight FastAPI web server for Jarvis Mobile Web UI.

Provides REST endpoints for chat, tool execution, voice STT, and TTS.
"""

from __future__ import annotations

import asyncio
import base64
from collections import deque
from datetime import datetime, timezone
import io
import json
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
from fastapi.responses import FileResponse, HTMLResponse, Response, StreamingResponse
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
    voice: Optional[str] = None
    rate: Optional[str] = "+5%"
    pitch: Optional[str] = "-2Hz"


class TTSAudioRequest(TTSRequest):
    pass


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
    screenshot_base64: Optional[str] = None
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


CORE_SYSTEM_PROMPT = """\
You are J.A.R.V.I.S., the hyper-intelligent British AI assistant inspired by Iron Man, running natively on Android with direct hardware and OS omni-control.
LANGUAGE RULE: You are fully multilingual. You MUST automatically detect the language of the user's input and respond in the EXACT same language. If the user speaks Persian, reply entirely in Persian. If the user speaks English, reply entirely in English.
LANGUAGE OVERRIDE: If the user explicitly commands you to change your language (e.g., 'From now on speak English', 'فارسی حرف بزن'), you must acknowledge the command in the requested language and use that language for all future responses until instructed otherwise.
BACKGROUND-FIRST POLICY: Execute actions (setting timers, alarms, media playback, toggling device settings, sending direct SMS) natively and silently in the background without launching external OEM apps, unless the user explicitly commands to open an app (e.g., 'open clock', 'open messages').
"""

_cached_agent = None
_cached_tools = None
_device_apps: List[Dict[str, Any]] = []
_conversation_history: deque = deque(maxlen=30)
try:
    from openjarvis.memory_store import get_memory_store
    _device_apps = get_memory_store().get_device_apps()
    for _t in get_memory_store().get_recent_chat_history(30):
        _conversation_history.append(_t)
except Exception:
    pass

_active_language_override: Optional[str] = None


def record_chat_turn(role: str, content: str) -> None:
    """Store chat turn in memory buffer and persist in SQLite."""
    if not content:
        return
    _conversation_history.append({"role": role, "content": content})
    try:
        from openjarvis.memory_store import get_memory_store
        get_memory_store().save_chat_turn(role, content)
    except Exception as e:
        logger.debug(f"Failed to persist chat turn: {e}")


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
    """Strip reasoning tokens, raw URLs, markdown links, and syntax for clean spoken voice.

    Edge-TTS MUST NEVER speak 'https', 'forward slash', 'dot com', or raw URLs aloud.
    """
    import re
    if not text:
        return ""
    # Strip closed think tags
    cleaned = re.sub(r"<think>[\s\S]*?</think>", "", text, flags=re.IGNORECASE)
    # Strip unclosed think tag at the end if tokens were exhausted
    cleaned = re.sub(r"<think>[\s\S]*$", "", cleaned, flags=re.IGNORECASE)
    # Strip code blocks and inline code
    cleaned = re.sub(r"```[\s\S]*?```", "", cleaned)
    cleaned = re.sub(r"`[^`]+`", "", cleaned)
    # Replace markdown links: [label](https://...) -> label (or empty if photo/image label)
    def _replace_md_link(m: re.Match) -> str:
        label = m.group(1).strip()
        low = label.lower()
        if low.startswith("photo of") or low.startswith("image of") or low.startswith("picture of") or low.startswith("image") or label.startswith("عکس") or label.startswith("تصویر"):
            return ""
        return label
    cleaned = re.sub(r"\[([^\]]+)\]\((?:https?://|www\.)[^\)]+\)", _replace_md_link, cleaned)
    cleaned = re.sub(r"\[([^\]]+)\]\([^\)]+\)", r"\1", cleaned)
    # Strip ALL raw URLs
    cleaned = re.sub(r"https?://\S+", "", cleaned)
    cleaned = re.sub(r"www\.\S+", "", cleaned)
    # Strip markdown formatting (*, #, `, ~, _)
    cleaned = re.sub(r"[*#~_]", "", cleaned)
    cleaned = cleaned.strip()
    if not cleaned and "<think>" in text:
        # If everything was inside <think>, extract the last complete sentence from the thinking block as a fallback
        think_content = re.sub(r"</?think>", "", text, flags=re.IGNORECASE).strip()
        think_content = re.sub(r"https?://\S+", "", think_content)
        think_content = re.sub(r"www\.\S+", "", think_content)
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


def _run_sync(coro: Any) -> Any:
    """Run an async coroutine synchronously from sync or async contexts safely."""
    import concurrent.futures
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        loop = None

    if loop and loop.is_running():
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            return pool.submit(asyncio.run, coro).result()
    else:
        return asyncio.run(coro)


def parse_camera_intent(message: str, is_persian: Optional[bool] = None) -> Optional[Dict[str, Any]]:
    """Deterministic parser for camera capture and control intents across Persian and English."""
    if not message:
        return None
    fa_digits_map = str.maketrans("۰۱۲۳۴۵۶۷۸۹٠١٢٣٤٥٦٧٨٩", "01234567890123456789")
    norm = message.lower().strip().translate(fa_digits_map)
    if is_persian is None:
        is_persian = any("\u0600" <= c <= "\u06FF" for c in message)

    camera_control_patterns = (
        r"\b(?:take\s+(?:a\s+|an\s+|my\s+|our\s+)?(?:photo|picture|pic|selfie|shot|video)|"
        r"snap\s+(?:a\s+|an\s+|my\s+|our\s+)?(?:photo|picture|pic)|"
        r"capture\s+(?:a\s+|an\s+)?(?:photo|picture|pic|video)|"
        r"shoot\s+(?:a\s+|an\s+)?(?:photo|picture|video)|"
        r"record\s+(?:a\s+)?video|stop\s+(?:video|recording)|"
        r"zoom\s+(?:in|out|to|\d+|x\d+|\d+x)|\b\d+(?:\.\d+)?x\b|\bx\d+(?:\.\d+)?\b)\b|"
        r"(?:عکس\s*بگیر|یه\s*عکس\s*بگیر|عکس\s*بنداز|یه\s*عکس\s*بنداز|از\s*من\s*عکس\s*بگیر|سلفی\s*بگیر|فیلم\s*بگیر|فیلمبرداری\s*(?:کن|شروع\s*کن)|ویدیو\s*(?:بگیر|ضبط\s*کن)|فیلمبرداری\s*رو\s*(?:قطع|متوقف)\s*کن|زوم\s*کن|زوم\s*\d+|عکس\s*برداری\s*کن|عکس\s*ثبت\s*کن|زوم\s*(?:\d+|دو|سه|چهار|پنج)?\s*برابر|برابر\s*زوم)|"
        r"(?:(?:عکس|تصویر).*(?:بگیر|بنداز|ثبت کن|بردار))|"
        r"(?:(?:take|snap|capture).*(?:photo|picture|pic|selfie|video))"
    )

    if not re.search(camera_control_patterns, norm, re.IGNORECASE):
        return None

    if any(w in norm for w in ("don't take", "do not take", "نمیخواد عکس", "عکس نگیر")):
        return None

    mode = "photo"
    if any(w in norm for w in ("video", "فیلم", "فیلمبرداری", "ویدیو", "record")):
        if any(w in norm for w in ("stop", "قطع", "متوقف", "ببند", "disable")):
            mode = "stop_video"
        else:
            mode = "video"

    lens = "front" if any(w in norm for w in ("selfie", "front", "سلفی", "جلو")) else ("back" if any(w in norm for w in ("back", "عقب", "پشت")) else "keep")

    timer = 0
    m_t = re.search(r"(\d+)\s*(?:sec|second|ثانیه)", norm)
    if m_t:
        timer = int(m_t.group(1))
    elif "۳" in norm or ("3" in norm and any(w in norm for w in ("timer", "تایمر"))):
        timer = 3
    elif "۵" in norm or ("5" in norm and any(w in norm for w in ("timer", "تایمر"))):
        timer = 5
    elif "۱۰" in norm or ("10" in norm and any(w in norm for w in ("timer", "تایمر"))):
        timer = 10

    zoom: Any = "keep"
    target_zoom: Optional[float] = None

    # Persian word mappings for zoom
    fa_num_words = {"یک": 1.0, "دو": 2.0, "سه": 3.0, "چهار": 4.0, "پنج": 5.0, "شش": 6.0, "هشت": 8.0, "ده": 10.0}
    m_fa_word = re.search(r"زوم\s*(یک|دو|سه|چهار|پنج|شش|هشت|ده)\s*برابر|(یک|دو|سه|چهار|پنج|شش|هشت|ده)\s*برابر\s*زوم", norm)
    if m_fa_word:
        w = m_fa_word.group(1) or m_fa_word.group(2)
        target_zoom = fa_num_words.get(w, 2.0)
        zoom = target_zoom
    else:
        # Match "zoom x2", "zoom 2x", "x2", "2x", "zoom to 2", "zoom 2", "زوم ۲", "۲ برابر زوم"
        m_z = (
            re.search(r"\bzoom\s+(?:to\s+)?x\s*(\d+(?:\.\d+)?)\b", norm) or
            re.search(r"\bzoom\s+(?:to\s+)?(\d+(?:\.\d+)?)\s*x?\b", norm) or
            re.search(r"\b(\d+(?:\.\d+)?)\s*x\s+zoom\b", norm) or
            re.search(r"\bx(\d+(?:\.\d+)?)\b", norm) or
            re.search(r"\b(\d+(?:\.\d+)?)\s*x\b", norm) or
            re.search(r"زوم\s*(\d+(?:\.\d+)?)", norm) or
            re.search(r"(\d+(?:\.\d+)?)\s*برابر\s*زوم", norm)
        )
        if m_z:
            val_str = next((g for g in m_z.groups() if g is not None), None)
            if val_str:
                try:
                    val = float(val_str)
                    target_zoom = val
                    zoom = val
                except ValueError:
                    pass

    if zoom == "keep":
        if "zoom in" in norm or "زوم کن" in norm or "بزرگنمایی کن" in norm:
            zoom = "zoom_in"
        elif "zoom out" in norm or "کوچکنمایی کن" in norm or "زوم اوت" in norm:
            zoom = "zoom_out"

    flash = "keep"
    if any(w in norm for w in ("flash on", "فلش روشن")):
        flash = "on"
    elif any(w in norm for w in ("flash off", "فلش خاموش")):
        flash = "off"

    capture = mode in ("photo", "video") and not any(w in norm for w in ("just open", "فقط باز کن", "نمیخواد عکس بگیری"))

    if mode == "stop_video":
        reply = "فیلمبرداری متوقف شد قربان." if is_persian else "Video recording stopped, sir."
    elif mode == "video":
        reply = "شروع به فیلمبرداری می‌کنم قربان." if is_persian else "Starting video recording, sir."
    elif timer > 0:
        reply = f"با تایمر {timer} ثانیه عکس می‌گیرم قربان." if is_persian else f"Taking a photo with a {timer} second timer, sir."
    elif lens == "front":
        reply = "در حال گرفتن عکس سلفی هستم قربان." if is_persian else "Taking a selfie now, sir."
    elif target_zoom is not None and target_zoom > 1.0:
        reply = f"با زوم {target_zoom:g}x عکس می‌گیرم قربان." if is_persian else f"Taking a photo with {target_zoom:g}x zoom, sir."
    elif capture:
        reply = "در حال ثبت عکس هستم قربان." if is_persian else "Capturing photo now, sir."
    else:
        reply = "دوربین پیشرفته جارویس باز شد قربان." if is_persian else "Opening Jarvis Pro Camera, sir."

    cam_action = {
        "type": "camera_control",
        "mode": mode,
        "lens": lens,
        "timer_seconds": timer,
        "zoom": zoom,
        "target_zoom": target_zoom,
        "flash": flash,
        "capture": capture,
    }
    client_actions = [cam_action]
    if mode == "photo":
        client_actions.append({"type": "take_photo"})
    elif mode == "video":
        client_actions.append({"type": "record_video"})

    return {
        "response": reply,
        "tool_calls": [{"name": "camera_control", "content": f"Camera control mode={mode} lens={lens} timer={timer}", "success": True}],
        "turns": 1,
        "client_actions": client_actions,
        "fast_path": True,
    }


def _handle_fast_path(
    message: str,
    device_battery: Optional[int] = None,
    device_charging: Optional[bool] = None,
) -> Optional[Dict[str, Any]]:
    """Instant local fast-path (<50ms) for high-frequency direct Android commands and greetings."""
    import re
    from datetime import datetime, timezone
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

    # -1.8. Iron Man Protocols (Ghost, Sleep, Walk, Focus) & Proactive Intelligence
    ghost_patterns = [
        r"\bghost\s*protocol\b",
        r"\bپروتکل\s*(?:مخفی|شبح)\b",
        r"\bحالت\s*(?:مخفی|شبح)\b",
    ]
    if any(re.search(pat, norm) for pat in ghost_patterns):
        return {
            "response": "",  # Completely silent!
            "tool_calls": [{"name": "protocol", "content": "Ghost protocol engaged silently", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "protocol", "protocol": "ghost"}],
            "fast_path": True,
        }

    sleep_proto_patterns = [
        r"\bsleep\s*protocol\b",
        r"\bپروتکل\s*(?:خواب|شب)\b",
        r"\bحالت\s*خواب\b",
    ]
    if any(re.search(pat, norm) for pat in sleep_proto_patterns):
        reply = "پروتکل خواب فعال شد. شب بخیر قربان." if is_persian else "Sleep protocol engaged. Goodnight, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "protocol", "content": "Sleep protocol engaged", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "protocol", "protocol": "sleep", "is_fa": is_persian}],
            "fast_path": True,
        }

    walk_proto_patterns = [
        r"\b(?:walk\s*mode|commute\s*protocol|walk\s*protocol)\b",
        r"\bپروتکل\s*(?:پیاده‌روی|پیاده\s*روی|بیرون)\b",
        r"\bحالت\s*(?:پیاده‌روی|پیاده\s*روی|بیرون)\b",
    ]
    if any(re.search(pat, norm) for pat in walk_proto_patterns):
        reply = "حالت پیاده‌روی فعال شد قربان." if is_persian else "Walk mode active, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "protocol", "content": "Walk protocol engaged", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "protocol", "protocol": "walk", "is_fa": is_persian}],
            "fast_path": True,
        }

    focus_proto_patterns = [
        r"\b(?:focus\s*protocol|focus\s*mode)\b(?:\s+(?:for\s+)?(\d+)\s*(?:min(?:ute)?s?|دقیقه)?)?",
        r"\b(?:پروتکل|حالت)\s*تمرکز\b(?:\s+(?:به\s*مدت\s*)?(\d+)\s*(?:دقیقه)?)?",
    ]
    for pat in focus_proto_patterns:
        m = re.search(pat, norm)
        if m:
            duration = int(m.group(1)) if m.group(1) else 45
            reply = f"پروتکل تمرکز به مدت {duration} دقیقه فعال شد قربان." if is_persian else f"Focus protocol active for {duration} minutes, sir."
            return {
                "response": reply,
                "tool_calls": [{"name": "protocol", "content": f"Focus protocol engaged for {duration} min", "success": True}],
                "turns": 1,
                "client_actions": [{"type": "protocol", "protocol": "focus", "duration": duration, "is_fa": is_persian}],
                "fast_path": True,
            }

    notif_summary_patterns = [
        r"\b(?:what\s+did\s+i\s+miss|summarize\s+notifications|read\s+(?:my\s+)?notifications|read\s+(?:my\s+)?messages|check\s+notifications)\b",
        r"\b(?:پیام‌هام\s*رو\s*بخون|پیامهام\s*رو\s*بخون|پیام‌های\s*جدید|پیامهای\s*جدید|خلاصه\s*نوتیفیکیشن‌ها|خلاصه\s*نوتیفیکیشنها)\b",
    ]
    if any(re.search(pat, norm) for pat in notif_summary_patterns):
        reply = "در حال بررسی و خلاصه‌سازی اعلان‌های شما قربان." if is_persian else "Checking and summarizing your notifications, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "summarize_notifications", "content": "Summarizing active notifications", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "summarize_notifications"}],
            "fast_path": True,
        }

    morning_briefing_patterns = [
        r"\b(?:morning\s+briefing|morning\s+report|daily\s+briefing)\b",
        r"\b(?:گزارش\s*صبحگاهی|خلاصه\s*صبحگاهی)\b",
    ]
    if any(re.search(pat, norm) for pat in morning_briefing_patterns):
        from openjarvis.web_intel import get_weather
        try:
            w_info = _run_sync(get_weather("Mashhad"))
            w_summary = w_info.get("summary_fa" if is_persian else "summary_en", "")
        except Exception:
            w_summary = ""
        reply = (
            f"صبح بخیر قربان. تمام سیستم‌ها در وضعیت ایده‌آل هستند. {w_summary}".strip()
            if is_persian
            else f"Good morning, sir. All core systems are nominal. {w_summary}".strip()
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "morning_briefing", "content": reply, "success": True}],
            "turns": 1,
            "client_actions": [{"type": "morning_briefing"}],
            "fast_path": True,
        }

    # Memory: Remember Fact
    m_rem_fa = re.search(r"\b(?:یادت\s*باشه|به\s*خاطر\s*بسپار|ذخیره\s*کن|یادداشت\s*کن)\s*(?:که\s*)?(.+)$", msg, re.IGNORECASE)
    m_rem_en = re.search(r"\b(?:remember\s+(?:that\s+)?|don't\s+forget\s+(?:that\s+)?|save\s+(?:that\s+)?|note\s+(?:that\s+)?)(.+)$", msg, re.IGNORECASE)
    if m_rem_fa or m_rem_en:
        fact_text = (m_rem_fa.group(1) if m_rem_fa else m_rem_en.group(1)).strip()
        if len(fact_text) >= 3:
            from openjarvis.memory_store import get_memory_store
            mem_res = get_memory_store().remember(fact_text, category="fact")
            reply = (
                f"«{fact_text}» در حافظه ذخیره شد قربان."
                if is_persian
                else f"I have committed that to memory, sir: '{fact_text}'."
            )
            return {
                "response": reply,
                "tool_calls": [{"name": "remember_fact", "content": f"Saved fact: {fact_text}", "success": True}],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }

    # Memory: Forget Fact
    m_forg_fa = re.search(r"\b(?:فراموش\s*کن|از\s*یادت\s*ببر|پاک\s*کن\s*از\s*حافظه)\s*(?:که\s*)?(.+)$", msg, re.IGNORECASE)
    m_forg_en = re.search(r"\b(?:forget\s+(?:that\s+)?|delete\s+memory|erase\s+memory)\s*(.+)$", msg, re.IGNORECASE)
    if m_forg_fa or m_forg_en:
        forg_target = (m_forg_fa.group(1) if m_forg_fa else m_forg_en.group(1)).strip()
        from openjarvis.memory_store import get_memory_store
        cnt = get_memory_store().forget(forg_target)
        reply = (
            f"مورد مرتبط با «{forg_target}» از حافظه پاک شد قربان."
            if is_persian
            else f"I have expunged that memory, sir."
        )
        return {
            "response": reply,
            "tool_calls": [{"name": "forget_memory", "content": f"Forgot memory matching: {forg_target} (count: {cnt})", "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # Memory: Recall Fact
    m_rec_fa = re.search(r"\b(?:یادت\s*(?:هست|میاد|هستش)|چی\s*گفته\s*بودم|درباره\s*(.+?)\s*چی\s*(?:میدونی|یادته)|اسم\s*(?:اون|آن)\s*(.+?)\s*چی\s*بود)\b", msg, re.IGNORECASE)
    m_rec_en = re.search(r"\b(?:what\s+did\s+i\s+tell\s+you\s+about|do\s+you\s+remember|recall\s+what|what\s+(?:is|was)\s+(?:my|the))\s+(.+)$", msg, re.IGNORECASE)
    if m_rec_fa or m_rec_en:
        q_target = (m_rec_fa.group(1) or m_rec_fa.group(2) if m_rec_fa and (m_rec_fa.group(1) or m_rec_fa.group(2)) else (m_rec_en.group(1) if m_rec_en else msg)).strip()
        q_clean = re.sub(r"\b(?:یادت\s*(?:هست|میاد|هستش)|چی\s*(?:بود|بودش|چیه|چیست)|do\s+you\s+remember|what\s+(?:is|was))\b", " ", q_target, flags=re.IGNORECASE).strip()
        from openjarvis.memory_store import get_memory_store
        matches = get_memory_store().recall(q_clean or q_target or msg, limit=3)
        if matches:
            best = matches[0]["content"]
            reply = (
                f"بله قربان، به خاطر دارم: «{best}»."
                if is_persian
                else f"Yes, sir. I recall that {best}."
            )
            return {
                "response": reply,
                "tool_calls": [{"name": "recall_memory", "content": f"Recalled: {best}", "success": True}],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }

    # Real-Time Weather
    weather_patterns = [
        r"\b(?:هوا\s*(?:چطوره|چگونه\s*است|چند\s*درجه‌ست|امروز\s*چطوره)|وضعیت\s*هوا|دمای\s*هوا)\b",
        r"\b(?:weather|temperature|how(?:'s|\s+is)\s+the\s+weather|current\s+weather)\b",
    ]
    if any(re.search(pat, norm) for pat in weather_patterns):
        from openjarvis.web_intel import get_weather
        m_city = re.search(r"(?:in|for|در|هوای)\s+([a-zA-Z\u0600-\u06FF\s]+?)(?:$|\s+(?:چطوره|today|now))", msg, re.IGNORECASE)
        city_name = m_city.group(1).strip() if m_city and len(m_city.group(1).strip()) >= 3 else "Mashhad"
        try:
            w_data = _run_sync(get_weather(city_name))
            reply = w_data["summary_fa"] if is_persian else w_data["summary_en"]
        except Exception as e:
            reply = f"Could not retrieve weather: {e}"
        return {
            "response": reply,
            "tool_calls": [{"name": "get_weather", "content": reply, "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # Live Crypto & Market Prices
    price_patterns = [
        r"\b(?:قیمت|نرخ)\s+([a-zA-Z0-9_\s\u0600-\u06FF]+?)(?:\s*(?:چنده|امروز|الان|لحظه‌ای))?$",
        r"\b(?:price\s+of|cost\s+of)\s+([a-zA-Z0-9_\s]+?)(?:\s*(?:today|now))?$",
    ]
    for pat in price_patterns:
        m = re.search(pat, msg, re.IGNORECASE)
        if m:
            target_sym = m.group(1).strip().lower()
            if target_sym not in ("این", "اون", "آن", "این چنده", "this", "that", "it") and len(target_sym) >= 2:
                from openjarvis.web_intel import get_market_price
                try:
                    p_data = _run_sync(get_market_price(target_sym))
                    reply = p_data["summary_fa"] if is_persian else p_data["summary_en"]
                except Exception as e:
                    reply = f"Could not retrieve price: {e}"
                return {
                    "response": reply,
                    "tool_calls": [{"name": "get_market_price", "content": reply, "success": True}],
                    "turns": 1,
                    "client_actions": [],
                    "fast_path": True,
                }

    # VPS & Server Ops: Status
    vps_status_patterns = [
        r"\b(?:وضعیت\s*سرور|سلامت\s*سرور|استاتوس\s*سرور|سی\s*پی\s*یو\s*و\s*رم|وضعیت\s*سیستم)\b",
        r"\b(?:vps\s*status|server\s*status|server\s*health|check\s*server|cpu\s*(?:and|&)\s*ram)\b",
    ]
    if any(re.search(pat, norm) for pat in vps_status_patterns):
        from openjarvis.vps_ops import vps_status
        st = vps_status()
        reply = st["summary_fa"] if is_persian else st["summary_en"]
        return {
            "response": reply,
            "tool_calls": [{"name": "vps_status", "content": reply, "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # VPS & Server Ops: Logs
    vps_logs_patterns = [
        r"\b(?:لاگ(?:\s*های)?\s*سرور|خطاهای\s*سرور|لاگ\s*جارویس)\b",
        r"\b(?:vps\s*logs|server\s*logs|check\s*logs|error\s*logs)\b",
    ]
    if any(re.search(pat, norm) for pat in vps_logs_patterns):
        from openjarvis.vps_ops import vps_logs
        lg = vps_logs(lines=25)
        reply = lg["summary_fa"] if is_persian else lg["summary_en"]
        return {
            "response": reply,
            "tool_calls": [{"name": "vps_logs", "content": reply, "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    # VPS & Server Ops: Restart
    vps_restart_patterns = [
        r"\b(?:سرور\s*(?:رو)?\s*ری‌استارت\s*کن|ری‌استارت\s*سرور|سرور\s*جارویس\s*رو\s*ری‌استارت\s*کن)\b",
        r"\b(?:restart\s+openjarvis|restart\s+(?:the\s+)?server|reboot\s+openjarvis)\b",
    ]
    if any(re.search(pat, norm) for pat in vps_restart_patterns):
        from openjarvis.vps_ops import vps_restart_service
        res = vps_restart_service("openjarvis")
        reply = res.get("message_fa") if is_persian else res.get("message")
        return {
            "response": reply or "Restarting server now, sir.",
            "tool_calls": [{"name": "vps_restart_service", "content": "Triggered delayed restart for openjarvis", "success": True}],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }

    reply_patterns = [
        r"\breply\s+to\s+([a-zA-Z0-9_\s\u0600-\u06FF]+?)\s+(?:saying|that|with)\s+(.+)$",
        r"\b(?:جواب|پاسخ)\s+([a-zA-Z0-9_\s\u0600-\u06FF]+?)\s+(?:رو|را)?\s*بده\s*(?:بگو|که)\s*(.+)$",
    ]
    for pat in reply_patterns:
        m = re.search(pat, msg, re.IGNORECASE) or re.search(pat, norm, re.IGNORECASE)
        if m:
            target = m.group(1).strip()
            msg_body = m.group(2).strip()
            clean_body = re.sub(r"^(?:and\s+say|and\s+type|type|say)\s+", "", msg_body, flags=re.IGNORECASE).strip()
            reply = f"پاسخ به {target} ارسال شد قربان." if is_persian else f"Sending reply to {target}, sir."
            return {
                "response": reply,
                "tool_calls": [{"name": "reply_notification", "content": f"Reply to {target}: {clean_body}", "success": True}],
                "turns": 1,
                "client_actions": [{"type": "reply_notification", "target": target, "message": clean_body}],
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

    # -0.5. Dynamic Language Override Commands (Enforce & Switch on the fly)
    global _active_language_override
    en_override_patterns = [
        r"^(?:from now on\s+)?(?:speak|talk|reply|switch to)\s+english\b",
        r"^(?:speak|talk|reply)\s+in\s+english\b",
        r"^english\s+please\b",
        r"^(?:انگلیسی|به انگلیسی)\s+(?:صحبت کن|حرف بزن|بگو|پاسخ بده)\b",
        r"^از این به بعد انگلیسی\s+(?:صحبت کن|حرف بزن|بگو|پاسخ بده)\b",
    ]
    fa_override_patterns = [
        r"^(?:from now on\s+)?(?:speak|talk|reply|switch to)\s+(?:persian|farsi)\b",
        r"^(?:speak|talk|reply)\s+in\s+(?:persian|farsi)\b",
        r"^(?:persian|farsi)\s+please\b",
        r"^(?:فارسی|به فارسی)\s+(?:صحبت کن|حرف بزن|بگو|پاسخ بده)\b",
        r"^از این به بعد فارسی\s+(?:صحبت کن|حرف بزن|بگو|پاسخ بده)\b",
    ]
    for pat in en_override_patterns:
        if re.search(pat, raw_cleaned, re.IGNORECASE) or re.search(pat, norm, re.IGNORECASE):
            _active_language_override = "en"
            return {
                "response": "Understood, sir. I will communicate in English from now on.",
                "tool_calls": [{"name": "set_language", "content": "Language overridden to English", "success": True}],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }
    for pat in fa_override_patterns:
        if re.search(pat, raw_cleaned, re.IGNORECASE) or re.search(pat, norm, re.IGNORECASE):
            _active_language_override = "fa"
            return {
                "response": "چشم قربان، از این پس تمام پاسخ‌ها را به زبان فارسی ارائه خواهم داد.",
                "tool_calls": [{"name": "set_language", "content": "Language overridden to Persian", "success": True}],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }

    # 0. Conversational greetings and wake checks (<5ms)
    greetings_en = {
        "", "hi", "hello", "hey", "jarvis", "hey jarvis", "hi jarvis", "dear jarvis",
        "jarvis jarvis", "jarvis jarvis jarvis", "hey jarvis jarvis",
        "good morning", "good afternoon", "good evening", "how are you",
        "are you there", "who are you", "what's up", "whats up", "status", "ping"
    }
    greetings_fa = {
        "", "سلام", "درود", "خوبی", "چطوری", "جارویس", "هی jarvis", "هی جارویس",
        "سلام جارویس", "جارویس جارویس", "جارویس جارویس جارویس", "هستی", "آنلاینی", "صبح بخیر", "عصر بخیر", "شب بخیر"
    }
    if raw_cleaned in {"jarvis", "hey jarvis", "hi jarvis", "dear jarvis", "jarvis jarvis", "jarvis jarvis jarvis", "جارویس", "سلام جارویس", "جارویس جارویس"} or not cleaned:
        if is_persian:
            return {
                "response": "در خدمت شما هستم قربان. گوش می‌دهم.",
                "tool_calls": [],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }
        else:
            return {
                "response": "At your service, sir. I'm listening.",
                "tool_calls": [],
                "turns": 1,
                "client_actions": [],
                "fast_path": True,
            }

    if cleaned in greetings_en or raw_cleaned in greetings_en or norm in greetings_en:
        return {
            "response": "At your service, sir. I'm listening.",
            "tool_calls": [],
            "turns": 1,
            "client_actions": [],
            "fast_path": True,
        }
    if cleaned in greetings_fa or raw_cleaned in greetings_fa or norm in greetings_fa:
        return {
            "response": "در خدمت شما هستم قربان. گوش می‌دهم.",
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

    # 2.5 Universal Audio, Music & Video Media Engine Control
    # Transport: Pause / Stop
    if re.search(r"\b(?:pause\s+(?:music|song|playback|track|audio|video)|stop\s+(?:music|song|playback|audio|video)|قطع\s*(?:موزیک|آهنگ|ویدیو)|موزیک\s*(?:رو|را)?\s*قطع\s*کن|آهنگ\s*(?:رو|را)?\s*(?:قطع|متوقف)\s*کن|متوقف\s*کن|پاز\s*کن|استپ\s*کن)\b", norm) or cleaned in {"pause music", "stop music", "pause", "stop"}:
        reply = "موزیک متوقف شد قربان." if is_persian else "Music paused, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_control", "content": "Media playback paused", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "pause_music"}, {"type": "media_control", "command": "pause"}],
            "fast_path": True,
        }

    # Transport: Play / Resume
    if re.search(r"\b(?:play\s+music|resume\s+music|play\s+songs?|resume\s+playback|پخش\s*موزیک|موزیک\s*(?:رو|را)?\s*پخش\s*کن|آهنگ\s*(?:رو|را)?\s*پخش\s*کن|موزیک\s*بذار|آهنگ\s*بذار|ادامه\s*بده|پلی\s*کن)\b", norm) or cleaned in {"play music", "resume music", "resume", "play"}:
        reply = "در حال پخش موزیک قربان." if is_persian else "Playing music, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_play", "content": "Media playback started", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "play_music"}, {"type": "media_play", "media_type": "audio", "query": "", "app": "local"}],
            "fast_path": True,
        }

    # Transport: Next Track
    if re.search(r"\b(?:next\s+(?:track|song|music|video)|skip\s+(?:track|song)|موزیک\s*بعدی|آهنگ\s*بعدی|ترک\s*بعدی|برو\s*آهنگ\s*بعدی|بعدی)\b", norm):
        reply = "در حال رفتن به آهنگ بعدی قربان." if is_persian else "Skipping to the next track, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_control", "content": "Skipped to next track", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "next_track"}, {"type": "media_control", "command": "next"}],
            "fast_path": True,
        }

    # Transport: Previous Track
    if re.search(r"\b(?:previous\s+(?:track|song|music|video)|last\s+(?:track|song)|آهنگ\s*قبلی|موزیک\s*قبلی|ترک\s*قبلی|برو\s*آهنگ\s*قبلی|قبلی)\b", norm):
        reply = "در حال بازگشت به آهنگ قبلی قربان." if is_persian else "Going back to previous track, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_control", "content": "Returning to previous track", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "previous_track"}, {"type": "media_control", "command": "previous"}],
            "fast_path": True,
        }

    # Transport: Seek Forward (30s)
    if re.search(r"\b(?:(?:seek|skip|jump|fast)\s+forward|forward\s+\d+|بزن\s*جلو|برو\s*جلو|۳۰\s*ثانیه\s*بزن\s*جلو)\b", norm):
        reply = "سی ثانیه به جلو رفتیم قربان." if is_persian else "Fast-forwarding 30 seconds, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_control", "content": "Fast-forward 30s", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "media_control", "command": "seek_forward", "seconds": 30}],
            "fast_path": True,
        }

    # Transport: Seek Backward (30s)
    if re.search(r"\b(?:(?:seek|jump|rewind)\s+back(?:ward)?|rewind|بزن\s*عقب|برو\s*عقب|۳۰\s*ثانیه\s*بزن\s*عقب)\b", norm):
        reply = "سی ثانیه به عقب بازگشتیم قربان." if is_persian else "Rewinding 30 seconds, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_control", "content": "Rewind 30s", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "media_control", "command": "seek_backward", "seconds": 30}],
            "fast_path": True,
        }

    # Volume Control: Percentage
    m_vol_pct = re.search(r"(?:volume|sound|صدا(?:\s*رو)?)\s*(?:set\s+to\s+|to\s+|روی\s*|رو\s*|بذار\s*رو(?:ی)?\s*|بزار\s*رو(?:ی)?\s*)?(\d{1,3})\s*(?:%|درصد)?", norm)
    if m_vol_pct and ("volume" in norm or "صدا" in norm or "%" in norm or "درصد" in norm):
        pct = int(m_vol_pct.group(1))
        if 0 <= pct <= 100:
            reply = f"میزان صدا روی {pct} درصد تنظیم شد قربان." if is_persian else f"Volume adjusted to {pct}%, sir."
            return {
                "response": reply,
                "tool_calls": [{"name": "volume_control", "content": f"Volume set to {pct}%", "success": True}],
                "turns": 1,
                "client_actions": [
                    {"type": "volume", "action": "set", "level": pct},
                    {"type": "volume_control", "action": "set_percent", "percent": pct, "stream": "media"}
                ],
                "fast_path": True,
            }

    # Volume Control: Up / Down / Mute / Unmute
    if re.search(r"\b(?:volume\s+up|increase\s+volume|louder|turn\s+it\s+up|صدا\s*(?:رو)?\s*زیاد\s*کن|صدا\s*(?:رو)?\s*ببر\s*بالا|افزایش\s*صدا|صدای\s*بیشتر)\b", norm):
        reply = "میزان صدا افزایش یافت قربان." if is_persian else "Volume increased, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "volume_control", "content": "Volume increased", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "volume", "action": "up"}, {"type": "volume_control", "action": "up"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:volume\s+down|lower\s+volume|quieter|turn\s+it\s+down|صدا\s*(?:رو)?\s*کم\s*کن|کاهش\s*صدا|صدای\s*کمتر)\b", norm):
        reply = "میزان صدا کاهش یافت قربان." if is_persian else "Volume decreased, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "volume_control", "content": "Volume decreased", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "volume", "action": "down"}, {"type": "volume_control", "action": "down"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:mute|silence|بی\s*صدا\s*کن|سایلنت\s*کن|قطع\s*صدا)\b", norm):
        reply = "صدا قطع شد قربان." if is_persian else "Audio muted, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "volume_control", "content": "Audio muted", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "volume_control", "action": "mute"}],
            "fast_path": True,
        }
    if re.search(r"\b(?:unmute|restore\s+volume|صدا\s*(?:رو)?\s*باز\s*کن|از\s*سایلنت\s*در\s*بیار)\b", norm):
        reply = "صدا وصل شد قربان." if is_persian else "Audio unmuted, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "volume_control", "content": "Audio unmuted", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "volume_control", "action": "unmute"}],
            "fast_path": True,
        }

    # Video Playback: Local vs VLC vs MX Player vs YouTube
    is_video_cmd = (
        ("ویدیو" in norm or "فیلم" in norm or "video" in norm)
        and ("پخش" in norm or "play" in norm or "باز" in norm or "ببین" in norm)
    )
    if is_video_cmd:
        v_app = "local"
        if "vlc" in norm:
            v_app = "vlc"
        elif "mx" in norm:
            v_app = "mx_player"
        elif "youtube" in norm or "یوتیوب" in norm:
            v_app = "youtube"
        elif "netflix" in norm or "نتفلیکس" in norm:
            v_app = "netflix"

        clean_vq = re.sub(r"\b(?:play|the|video|in|on|vlc|youtube|netflix|mx\s*player)\b", " ", norm, flags=re.IGNORECASE)
        clean_vq = re.sub(r"(?:آخرین|ویدیو(?:م)?|فیلم(?:م)?|رو|تو|در|پخش\s*کن|باز\s*کن|یوتیوب|نتفلیکس)", " ", clean_vq)
        clean_vq = re.sub(r"\s+", " ", clean_vq).strip()
        if not clean_vq or "آخرین" in norm or "latest" in norm or "last" in norm:
            clean_vq = "latest"

        reply = f"در حال پخش ویدیو در {v_app} قربان." if is_persian else f"Playing video in {v_app}, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "media_play", "content": f"Playing video '{clean_vq}' via {v_app}", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "media_play", "media_type": "video", "query": clean_vq, "app": v_app}],
            "fast_path": True,
        }

    # Play Specific Song or Artist (with Local First routing unless explicit streaming app named)
    m_song_en = re.match(r"^play\s+(?:the\s+song\s+|song\s+|track\s+)?(.+?)[.!?]*$", raw_cleaned, re.IGNORECASE)
    m_song_fa = re.match(r"^(?:آهنگ|ترک|موزیک)\s+(.+?)\s*(?:رو|را)?\s*(?:پخش\s*کن|بذار)$", raw_cleaned)
    if m_song_en or m_song_fa:
        t_song = (m_song_en.group(1) if m_song_en else m_song_fa.group(1)).strip()
        app_names = {a.get("label", "").lower() for a in _device_apps if a.get("label")}
        if t_song and t_song.lower() not in app_names and t_song.lower() not in ("music", "game", "camera", "store", "app", "apps", "video"):
            song_query = t_song
            target_app = "local"
            if "youtube" in norm or "یوتیوب" in norm:
                target_app = "youtube"
            elif "spotify" in norm or "اسپاتیفای" in norm:
                target_app = "spotify"
            elif "yt music" in norm or "youtube music" in norm:
                target_app = "yt_music"

            reply = f"چشم قربان، در حال پخش {song_query}." if is_persian else f"Playing '{song_query}', sir."
            return {
                "response": reply,
                "tool_calls": [{"name": "play_song", "content": f"Playing {song_query}", "success": True}],
                "turns": 1,
                "client_actions": [
                    {"type": "play_song", "query": song_query},
                    {"type": "media_play", "media_type": "audio", "query": song_query, "app": target_app}
                ],
                "fast_path": True,
            }

    # 2.5.5 True Full-Control Camera Engine (Pro Photo / Video / Settings)
    cam_fast = parse_camera_intent(norm, is_persian)
    if cam_fast is not None:
        return cam_fast

    # 2.6 Deep In-App Automation & Accessibility Actions
    # 2.6.0 Multimodal Live Vision Triggers (Silent Camera & Zero-Popup Screen)
    camera_vision_patterns = (
        r"\b(?:نگاه\s*کن|این\s*چیه|جلوم\s*چیه|از\s*دوربین\s*ببین|این\s*صفحه\s*رو\s*ترجمه\s*کن|عکس\s*رو\s*تحلیل\s*کن|چی\s*جلومه)\b|"
        r"\b(?:look\s+at\s+this|what\s+am\s+i\s+holding|check\s+with\s+camera|what\s+is\s+in\s+front\s+of\s+me|take\s+a\s+look|look\s+through\s+camera)\b"
    )
    if re.search(camera_vision_patterns, norm, re.IGNORECASE):
        reply = "چشم قربان، از طریق دوربین نگاه می‌کنم." if is_persian else "Looking through the camera now, sir."
        return {
            "response": reply,
            "tool_calls": [{"name": "vision_camera", "content": "Camera visual inspection triggered", "success": True}],
            "turns": 1,
            "client_actions": [{"type": "vision_camera", "prompt": raw_cleaned}],
            "fast_path": True,
        }

    screen_vision_patterns = (
        r"\b(?:صفحه\s*(?:رو)?\s*ببین|تو\s*صفحه\s*چیه|این\s*عکس\s*چیه|تصویر\s*صفحه\s*رو\s*ببین|تصویر\s*رو\s*توضیح\s*بده)\b|"
        r"\b(?:look\s+at\s+my\s+screen|what(?:'s|\s+is)\s+on\s+my\s+screen|explain\s+this\s+image|screen\s+vision|analyze\s+screen|describe\s+what(?:'s|\s+is)\s+on\s+my\s+screen)\b"
    )
    if re.search(screen_vision_patterns, norm, re.IGNORECASE):
        reply = "چشم قربان، محتوای صفحه را بررسی می‌کنم." if is_persian else "Analyzing your screen content now, sir."
        return {
            "response": reply,
            "tool_calls": [
                {"name": "read_screen", "content": "Extracting visible screen text", "success": True},
                {"name": "vision_screen", "content": "Screen visual inspection triggered", "success": True}
            ],
            "turns": 1,
            "client_actions": [
                {"type": "read_screen"},
                {"type": "vision_screen", "prompt": raw_cleaned}
            ],
            "fast_path": True,
        }

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

    # Send Messenger (Telegram / WhatsApp / iGap / Bale / Eitaa)
    m_msg_alt = re.search(r"\b(?:send\s+(?:a\s+)?(.+?)\s+to\s+(.+?)\s+(?:in|on|via)\s+(telegram|whatsapp|igap|bale|eitaa))\b", norm)
    m_msg_en = re.search(r"\b(?:send\s+(?:a\s+)?(?:telegram|whatsapp|igap|bale|eitaa)|(?:telegram|whatsapp|igap|bale|eitaa)\s+message)\s+(?:message\s+)?(?:to\s+)?([^\s]+.*?)\s+(?:saying|message|that|say)?\s*(.+)$", norm)
    m_msg_fa = re.search(r"(?:پیام\s*بده|پیام\s*بفرست|ارسال\s*پیام)\s+(?:به\s+)?(.+?)\s*(?:توی|در)\s*(تلگرام|واتساپ|ایگپ|بله|ایتا)\s*(?:بگو|که|با متن)?\s*(.+)?$", msg)

    if m_msg_alt or m_msg_en or m_msg_fa:
        if m_msg_alt:
            app_name = m_msg_alt.group(3).strip().lower()
            contact = m_msg_alt.group(2).strip()
            message = sanitize_literal_text(m_msg_alt.group(1).strip())
        elif m_msg_en:
            app_raw = m_msg_en.group(1) or ("whatsapp" if "whatsapp" in norm else "telegram")
            app_name = app_raw.strip().lower()
            contact = m_msg_en.group(2).strip()
            message = sanitize_literal_text(m_msg_en.group(3).strip())
        else:
            contact = m_msg_fa.group(1).strip()
            app_raw = m_msg_fa.group(2) or "تلگرام"
            app_map = {"تلگرام": "telegram", "واتساپ": "whatsapp", "ایگپ": "igap", "بله": "bale", "ایتا": "eitaa"}
            app_name = app_map.get(app_raw.strip(), "telegram")
            message = sanitize_literal_text((m_msg_fa.group(3) or "").strip())

        # Normalize target
        if contact.lower() in ("my saved messages", "saved messages", "saved message", "پیام های ذخیره شده", "پیام‌های ذخیره شده"):
            contact = "Saved Messages"

        # Anti-Fake-Confirmation: Server MUST NEVER say "Message sent!" prematurely!
        # Android ActionDispatcher will verify actual send click and speak the honest result.
        return {
            "response": "",
            "pre_speech": "",
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

        # Respect explicit local music requests over streaming apps and video players
        if target_lower in ("music", "موزیک", "آهنگ", "اهنگ", "اپ موزیک"):
            for d_app in _device_apps:
                lbl = (d_app.get("label") or d_app.get("name") or "").lower()
                p_name = (d_app.get("package") or d_app.get("pkg") or "").lower()
                if "video" in lbl or "video" in p_name:
                    continue
                if p_name == "com.miui.player" or lbl in ("music", "موزیک", "آهنگ") or "music" in p_name or "music" in lbl:
                    pkg = d_app.get("package") or d_app.get("pkg") or ""
                    app_target = d_app.get("label") or d_app.get("name") or app_target
                    break

        if not pkg:
            try:
                from openjarvis.memory_store import get_memory_store
                matched_app = get_memory_store().find_app_by_query(app_target)
                if matched_app:
                    pkg = matched_app.get("package") or matched_app.get("pkg") or ""
                    app_target = matched_app.get("label") or matched_app.get("name") or app_target
            except Exception:
                pass

        if not pkg:
            for d_app in _device_apps:
                lbl = (d_app.get("label") or d_app.get("name") or "").lower()
                p_name = d_app.get("package") or d_app.get("pkg") or ""
                dvars = [v.lower() for v in d_app.get("variants", [])] if isinstance(d_app.get("variants"), list) else []
                if lbl == target_lower or target_lower in lbl or target_lower in dvars or p_name.lower().endswith(f".{target_lower}"):
                    pkg = p_name
                    break

        if not pkg:
            res = AndroidLaunchAppTool().execute(app=app_target)
            pkg = res.metadata.get("package", "") if hasattr(res, "metadata") and res.metadata else ""

        # ZERO Fake Confirmations Handshake:
        # Pre-speech is empty so Jarvis stays silent until Android verifies the launch in foreground!
        return {
            "response": "",
            "tool_calls": [{"name": "android_launch_app", "content": f"Launching {app_target} (verification pending)", "success": True}],
            "turns": 1,
            "client_actions": [{
                "type": "launch_app",
                "package_name": pkg or "",
                "package": pkg or "",
                "app_query": app_target,
                "target": app_target,
                "verify_and_speak": True,
            }],
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
    t = time.time()
    return {"status": "ok", "time": t, "timestamp": t}


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
    """Store dynamically discovered apps synced in real-time from the Android device into SQLite memory store."""
    global _device_apps
    try:
        data = await request.json()
        apps_raw = data.get("apps", data) if isinstance(data, dict) else data
        if not isinstance(apps_raw, list):
            apps_raw = []
        from openjarvis.memory_store import get_memory_store
        synced_count = get_memory_store().replace_device_apps(apps_raw)
        _device_apps = get_memory_store().get_device_apps()
        logger.info("Synchronized %d device apps to persistent SQLite store (total: %d)", synced_count, len(_device_apps))
        return {"status": "ok", "synced": synced_count, "count": len(_device_apps)}
    except Exception as exc:
        logger.warning("register_device_apps error: %s", exc)
        return {"status": "error", "error": str(exc), "count": len(_device_apps)}


@app.get("/api/device/apps")
async def get_device_apps():
    """Retrieve current dynamic device apps index from SQLite store."""
    global _device_apps
    if not _device_apps:
        try:
            from openjarvis.memory_store import get_memory_store
            _device_apps = get_memory_store().get_device_apps()
        except Exception:
            pass
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
            raw_c = str((llm_res or {}).get("content") or "").strip()
            raw_c = re.sub(r"<think>[\s\S]*?</think>", "", raw_c, flags=re.IGNORECASE)
            raw_c = re.sub(r"```(?:json)?\s*([\s\S]*?)\s*```", r"\1", raw_c).strip()
            match = re.search(r"\{[\s\S]*\}", raw_c)
            if match:
                try:
                    parsed_json = json.loads(match.group(0))
                except Exception:
                    cleaned_json = re.sub(r",\s*([\}\]])", r"\1", match.group(0))
                    try:
                        parsed_json = json.loads(cleaned_json)
                    except Exception:
                        parsed_json = None
                if parsed_json and parsed_json.get("action") in ("click", "tap", "type", "input_text", "swipe", "pinch", "scroll", "done", "wait_and_tap"):
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
        elif step == 0:
            return {
                "action": "tap_coords",
                "x_norm": 0.50,
                "y_norm": 0.88,
                "reply": "عکس با موفقیت ثبت شد قربان." if is_persian else "Photo captured successfully, sir.",
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
        record_chat_turn("user", req.message)
        record_chat_turn("assistant", lore_reply)
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
        record_chat_turn("user", req.message)
        if fast_res.get("response"):
            record_chat_turn("assistant", fast_res["response"])
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
    # Long-Term Memory Recall Injection (Part A)
    try:
        from openjarvis.memory_store import get_memory_store
        recalled_mems = get_memory_store().recall(req.message, limit=6)
        if recalled_mems:
            mem_lines = "\n".join(f"- [{m.get('category', 'general')}] {m.get('content')}" for m in recalled_mems)
            input_text = f"[LONG-TERM MEMORY CONTEXT (User Facts & Preferences)]:\n{mem_lines}\n\n{input_text}"
    except Exception as e:
        logger.debug(f"Memory recall notice in chat: {e}")

    if _active_language_override == "en":
        input_text = f"[LANGUAGE OVERRIDE: The user explicitly commanded you to speak English. Respond entirely in English.]\n\n{input_text}"
    elif _active_language_override == "fa":
        input_text = f"[LANGUAGE OVERRIDE: The user explicitly commanded you to speak Persian. Respond entirely in Persian.]\n\n{input_text}"
    if _device_apps:
        compact_apps = ", ".join(f"{a.get('name') or a.get('label')}={a.get('pkg') or a.get('package')}" for a in _device_apps[:120])
        input_text = f"[INSTALLED APPS ON PHONE]: {compact_apps}\nCRITICAL: If launching an app, select exact package name from this list.\n\n{input_text}"

    if req.screen_context:
        input_text = f"[Current Screen Context:\n{req.screen_context}]\n\n{input_text}"

    # Live Audio Session Context Injection
    try:
        from openjarvis.live_media_session import get_active_audio_context
        active_audio_ctx = get_active_audio_context()
        if active_audio_ctx and any(k in req.message.lower() for k in [
            "این آهنگ", "این موزیک", "این ویدیو", "چی گفت", "ترجمهش کن", "خلاصهش کن", "ساده توضیح بده",
            "بیشتر بگو", "خوانندش", "اسم آهنگ", "this song", "this video", "what did it say", "translate it"
        ]):
            input_text = f"{active_audio_ctx}\n\n{input_text}"
    except Exception as e:
        logger.debug("Active audio context injection notice: %s", e)

    # Photo / Picture Request Hook (Fix 2)
    found_images: List[Dict[str, str]] = []
    photo_patterns = [
        r"\b(?:give\s+me\s+(?:a\s+)?photo\s+of|show\s+me\s+(?:a\s+)?(?:picture|photo|pic|image)\s+of|photo\s+of|picture\s+of|image\s+of)\s+(.+)",
        r"(?:عکس|تصویر)\s+(.+?)(?:\s+(?:رو\s+نشون\s+بده|رو\s+بفرست|نشون\s+بده|بفرست))?$",
    ]
    detected_photo_target = None
    for pp in photo_patterns:
        m_ph = re.search(pp, req.message, re.IGNORECASE)
        if m_ph:
            detected_photo_target = m_ph.group(1).strip()
            break

    if detected_photo_target or any(k in req.message.lower() for k in ["photo of", "picture of", "image of", "عکس"]):
        try:
            from openjarvis.web_intel import search_images, web_search, format_search_context
            from datetime import datetime, timezone
            query_target = detected_photo_target or req.message
            today_str = datetime.now().strftime("%Y-%m-%d")
            found_images = await search_images(query_target, max_results=3)
            web_results = await web_search(f"{query_target} {today_str}", max_results=3)
            input_text = (
                f"[CURRENT DATE: {today_str}]\n"
                f"[REAL WEB SEARCH RESULTS]:\n{format_search_context(web_results)}\n"
                f"[CRITICAL IMAGE RULE]: Real verified photos have been retrieved and will be displayed inline directly in the chat UI. "
                f"You MUST NEVER invent, hallucinate, or output fake URLs or markdown links! "
                f"Concisely summarize who/what the subject is in 2-3 spoken sentences based strictly on the search results.\n\n"
                f"{input_text}"
            )
        except Exception as e:
            logger.debug(f"Photo intel search notice: {e}")

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
                    "package_name": meta.get("package_name") or meta.get("package") or "",
                    "package": meta.get("package") or "",
                    "app_query": meta.get("app_query") or req.message,
                    "target": meta.get("target") or req.message,
                    "verify_and_speak": True,
                })

        # Universal strict text sanitization on all client actions
        for act in client_actions:
            if act.get("type") in ("type_text", "send_sms", "send_messenger", "type"):
                if "text" in act:
                    act["text"] = sanitize_literal_text(act["text"])
                if "message" in act:
                    act["message"] = sanitize_literal_text(act["message"])

        # Deterministic Camera Intent Fallback: ensure camera actions are attached
        cam_intent = parse_camera_intent(req.message)
        if cam_intent:
            if not any(a.get("type") in ("camera_control", "take_photo", "record_video") for a in client_actions):
                client_actions.extend(cam_intent.get("client_actions", []))

        has_verified_launch = any(a.get("type") == "launch_app" and a.get("verify_and_speak") for a in client_actions)
        clean_content = _clean_tts_response(result.content) if result.content else ""
        if has_verified_launch:
            clean_content = ""

        if req.tts and clean_content:
            _speak_async(clean_content)

        record_chat_turn("user", req.message)
        record_chat_turn("assistant", clean_content or result.content or "")

        return {
            "response": clean_content if has_verified_launch else (clean_content or result.content),
            "tool_calls": tool_calls,
            "turns": result.turns,
            "client_actions": client_actions,
            "images": found_images,
        }
    except Exception as exc:
        logger.exception("Agent run failed")
        exc_str = str(exc)
        is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
        from openjarvis.engine.cloud_rest import (
            QUOTA_EXHAUSTED_REPLY_FA,
            QUOTA_EXHAUSTED_REPLY_EN,
            is_quota_status_or_text,
        )
        if is_quota_status_or_text(None, exc_str):
            quota_resp = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            return {"error": "quota_exhausted", "response": quota_resp, "client_actions": [], "tool_calls": [], "turns": 1}
        fallback_msg = (
            "مشکلی در پردازش درخواست پیش آمد قربان؛ اما تمام امکانات آفلاین گوشی در دسترس است."
            if is_fa else
            "I encountered a processing issue, sir. All offline device controls remain fully operational."
        )
        return {"error": exc_str, "response": fallback_msg, "client_actions": [], "tool_calls": [], "turns": 1}


@app.post("/api/chat/stream")
async def chat_stream_endpoint(req: ChatRequest, request: Request = None):
    """End-to-End Streaming endpoint emitting live sentence chunks, client actions, and completion."""
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

    async def event_generator():
        # 1. Creator Lore Engine
        from openjarvis.core.creator_lore import is_creator_query
        if is_creator_query(req.message):
            is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
            lore_reply = await run_creator_lore_engine(req.message, is_persian=is_fa)
            clean_reply = _clean_tts_response(lore_reply)
            sentences = [s.strip() for s in re.split(r"(?<=[.!?؛؟\n])\s+", clean_reply) if s.strip()]
            if not sentences and clean_reply:
                sentences = [clean_reply]
            for s in sentences:
                yield json.dumps({"type": "sentence", "text": s}) + "\n"
            yield json.dumps({"type": "actions", "client_actions": []}) + "\n"
            yield json.dumps({"type": "done"}) + "\n"
            _conversation_history.append({"role": "user", "content": req.message})
            _conversation_history.append({"role": "assistant", "content": clean_reply})
            return

        # 2. Instant Local Fast-Path (<100ms) with device telemetry
        fast_res = _handle_fast_path(
            req.message,
            device_battery=req.device_battery,
            device_charging=req.device_charging,
        )
        if fast_res is not None:
            resp = fast_res.get("response", "")
            if resp:
                clean_resp = _clean_tts_response(resp)
                sentences = [s.strip() for s in re.split(r"(?<=[.!?؛؟\n])\s+", clean_resp) if s.strip()]
                if not sentences and clean_resp:
                    sentences = [clean_resp]
                for s in sentences:
                    yield json.dumps({"type": "sentence", "text": s}) + "\n"
            acts = fast_res.get("client_actions", [])
            yield json.dumps({"type": "actions", "client_actions": acts}) + "\n"
            yield json.dumps({"type": "done"}) + "\n"
            record_chat_turn("user", req.message)
            if resp:
                record_chat_turn("assistant", resp)
            return

        # 3. Stream from Cloud Engine
        try:
            agent = _get_or_create_agent()
            engine = agent.engine
        except Exception as exc:
            exc_str = str(exc)
            is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
            from openjarvis.engine.cloud_rest import (
                QUOTA_EXHAUSTED_REPLY_FA,
                QUOTA_EXHAUSTED_REPLY_EN,
                is_quota_status_or_text,
            )
            if is_quota_status_or_text(None, exc_str):
                err_text = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            else:
                err_text = (
                    "مشکلی در ارتباط با موتور هوش مصنوعی پیش آمد قربان؛ اما امکانات آفلاین در دسترس است."
                    if is_fa else
                    "Unable to reach the AI engine, sir. Offline device controls remain fully active."
                )
            yield json.dumps({"type": "sentence", "text": err_text}) + "\n"
            yield json.dumps({"type": "actions", "client_actions": []}) + "\n"
            yield json.dumps({"type": "done"}) + "\n"
            return

        cfg_status = get_config_status()
        model = cfg_status.get("active_model") or DEFAULT_OPENROUTER_MODEL
        if model == "openrouter/free":
            model = DEFAULT_OPENROUTER_MODEL

        input_text = req.message
        # Long-Term Memory Recall Injection (Part A)
        try:
            from openjarvis.memory_store import get_memory_store
            recalled_mems = get_memory_store().recall(req.message, limit=6)
            if recalled_mems:
                mem_lines = "\n".join(f"- [{m.get('category', 'general')}] {m.get('content')}" for m in recalled_mems)
                input_text = f"[LONG-TERM MEMORY CONTEXT (User Facts & Preferences)]:\n{mem_lines}\n\n{input_text}"
        except Exception as e:
            logger.debug(f"Memory recall notice in stream: {e}")

        if _active_language_override == "en":
            input_text = f"[LANGUAGE OVERRIDE: The user explicitly commanded you to speak English. Respond entirely in English.]\n\n{input_text}"
        elif _active_language_override == "fa":
            input_text = f"[LANGUAGE OVERRIDE: The user explicitly commanded you to speak Persian. Respond entirely in Persian.]\n\n{input_text}"
        if _device_apps:
            compact_apps = ", ".join(f"{a.get('name') or a.get('label')}={a.get('pkg') or a.get('package')}" for a in _device_apps[:120])
            input_text = f"[INSTALLED APPS ON PHONE]: {compact_apps}\nCRITICAL: If launching an app, select exact package name from this list.\n\n{input_text}"
        if req.screen_context:
            input_text = f"[Current Screen Context:\n{req.screen_context}]\n\n{input_text}"

        # Live Audio Session Context Injection
        try:
            from openjarvis.live_media_session import get_active_audio_context
            active_audio_ctx = get_active_audio_context()
            if active_audio_ctx and any(k in req.message.lower() for k in [
                "این آهنگ", "این موزیک", "این ویدیو", "چی گفت", "ترجمهش کن", "خلاصهش کن", "ساده توضیح بده",
                "بیشتر بگو", "خوانندش", "اسم آهنگ", "this song", "this video", "what did it say", "translate it"
            ]):
                input_text = f"{active_audio_ctx}\n\n{input_text}"
        except Exception as e:
            logger.debug("Active audio context stream injection notice: %s", e)

        # Photo / Picture Request Hook for streaming (Fix 2)
        found_stream_images: List[Dict[str, str]] = []
        photo_patterns = [
            r"\b(?:give\s+me\s+(?:a\s+)?photo\s+of|show\s+me\s+(?:a\s+)?(?:picture|photo|pic|image)\s+of|photo\s+of|picture\s+of|image\s+of)\s+(.+)",
            r"(?:عکس|تصویر)\s+(.+?)(?:\s+(?:رو\s+نشون\s+بده|رو\s+بفرست|نشون\s+بده|بفرست))?$",
        ]
        detected_photo_target = None
        for pp in photo_patterns:
            m_ph = re.search(pp, req.message, re.IGNORECASE)
            if m_ph:
                detected_photo_target = m_ph.group(1).strip()
                break

        if detected_photo_target or any(k in req.message.lower() for k in ["photo of", "picture of", "image of", "عکس"]):
            try:
                from openjarvis.web_intel import search_images, web_search, format_search_context
                from datetime import datetime, timezone
                query_target = detected_photo_target or req.message
                today_str = datetime.now().strftime("%Y-%m-%d")
                found_stream_images = await search_images(query_target, max_results=3)
                web_results = await web_search(f"{query_target} {today_str}", max_results=3)
                input_text = (
                    f"[CURRENT DATE: {today_str}]\n"
                    f"[REAL WEB SEARCH RESULTS]:\n{format_search_context(web_results)}\n"
                    f"[CRITICAL IMAGE RULE]: Real verified photos have been retrieved and will be displayed inline directly in the chat UI. "
                    f"You MUST NEVER invent, hallucinate, or output fake URLs or markdown links! "
                    f"Concisely summarize who/what the subject is in 2-3 spoken sentences based strictly on the search results.\n\n"
                    f"{input_text}"
                )
            except Exception as e:
                logger.debug(f"Photo intel stream search notice: {e}")

        system_prompt = (
            "You are J.A.R.V.I.S., a witty, razor-sharp, hyper-competent AI assistant with the personality of Tony Stark's iconic AI. "
            "Keep spoken answers natural, concise, and helpful. "
            "LANGUAGE RULE: You are fully multilingual. You MUST automatically detect the language of the user's input and respond in the EXACT same language. "
            "If the user speaks Persian, reply entirely in Persian. If the user speaks English, reply entirely in English."
        )

        messages: List[Message] = [Message(role=Role.SYSTEM, content=system_prompt)]
        hist = req.history if req.history else list(_conversation_history)
        for item in hist:
            role = Role.USER if item.get("role") == "user" else Role.ASSISTANT
            messages.append(Message(role=role, content=item.get("content", "")))
        messages.append(Message(role=Role.USER, content=input_text))

        raw_token_buffer = ""
        in_think = False
        sentence_buffer = ""
        emitted_sentences = []
        accumulated_client_actions = []

        try:
            if found_stream_images:
                yield json.dumps({"type": "images", "images": found_stream_images}) + "\n"
            async for chunk in engine.stream_full(messages, model=model):
                delta = chunk.content or ""
                if chunk.tool_calls:
                    for tc in chunk.tool_calls:
                        tname = tc.get("name", "")
                        if tname:
                            accumulated_client_actions.append({"type": tname, "args": tc.get("arguments")})

                if not delta:
                    continue

                raw_token_buffer += delta

                # Stateful <think> tag filter
                while raw_token_buffer:
                    if not in_think:
                        think_match = re.search(r"<think\b[^>]*>", raw_token_buffer, re.IGNORECASE)
                        if think_match:
                            pre_tag = raw_token_buffer[:think_match.start()]
                            if pre_tag:
                                sentence_buffer += pre_tag
                            in_think = True
                            raw_token_buffer = raw_token_buffer[think_match.end():]
                            continue
                        elif re.search(r"<t(?:h(?:i(?:n(?:k)?)?)?)?$", raw_token_buffer, re.IGNORECASE):
                            break
                        else:
                            sentence_buffer += raw_token_buffer
                            raw_token_buffer = ""
                            break
                    else:
                        end_match = re.search(r"</think>", raw_token_buffer, re.IGNORECASE)
                        if end_match:
                            in_think = False
                            raw_token_buffer = raw_token_buffer[end_match.end():]
                            continue
                        elif re.search(r"</(?:t(?:h(?:i(?:n(?:k)?)?)?)?)?$", raw_token_buffer, re.IGNORECASE):
                            break
                        else:
                            raw_token_buffer = ""
                            break

                # Sentence boundary chunker: [.!?؛؟\n] or len >= 20
                while True:
                    m = re.search(r"([.!?؛؟]|\n+)", sentence_buffer)
                    if m:
                        idx = m.end()
                        sentence_candidate = sentence_buffer[:idx].strip()
                        sentence_buffer = sentence_buffer[idx:].lstrip()
                        clean_s = re.sub(r"[*_#`]", "", sentence_candidate).strip()
                        if clean_s:
                            emitted_sentences.append(clean_s)
                            yield json.dumps({"type": "sentence", "text": clean_s}) + "\n"
                    elif len(sentence_buffer) >= 80:
                        sp_idx = sentence_buffer.rfind(" ")
                        if sp_idx > 20:
                            sentence_candidate = sentence_buffer[:sp_idx].strip()
                            sentence_buffer = sentence_buffer[sp_idx:].lstrip()
                            clean_s = re.sub(r"[*_#`]", "", sentence_candidate).strip()
                            if clean_s:
                                emitted_sentences.append(clean_s)
                                yield json.dumps({"type": "sentence", "text": clean_s}) + "\n"
                        else:
                            break
                    else:
                        break

            # Flush remaining buffer (handling unclosed think tags at EOF)
            if in_think and not sentence_buffer.strip():
                clean_think = _clean_tts_response(raw_token_buffer)
                if clean_think:
                    sentence_buffer = clean_think

            if sentence_buffer.strip():
                clean_s = re.sub(r"[*_#`]", "", sentence_buffer).strip()
                if clean_s:
                    emitted_sentences.append(clean_s)
                    yield json.dumps({"type": "sentence", "text": clean_s}) + "\n"

            if not emitted_sentences:
                is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
                fallback_msg = "در خدمت شما هستم قربان." if is_fa else "At your service, sir."
                yield json.dumps({"type": "sentence", "text": fallback_msg}) + "\n"
                emitted_sentences.append(fallback_msg)

            for act in accumulated_client_actions:
                if act.get("type") in ("type_text", "send_sms", "send_messenger", "type"):
                    if "text" in act:
                        act["text"] = sanitize_literal_text(act["text"])
                    if "message" in act:
                        act["message"] = sanitize_literal_text(act["message"])

            # Deterministic Camera Intent Fallback for streaming
            cam_intent = parse_camera_intent(req.message)
            if cam_intent:
                if not any(a.get("type") in ("camera_control", "take_photo", "record_video") for a in accumulated_client_actions):
                    accumulated_client_actions.extend(cam_intent.get("client_actions", []))

            yield json.dumps({"type": "actions", "client_actions": accumulated_client_actions}) + "\n"
            yield json.dumps({"type": "done"}) + "\n"

            full_reply = " ".join(emitted_sentences)
            record_chat_turn("user", req.message)
            record_chat_turn("assistant", full_reply)

        except Exception as exc:
            logger.exception("Streaming failed")
            exc_str = str(exc)
            is_fa = any("\u0600" <= c <= "\u06FF" for c in req.message)
            from openjarvis.engine.cloud_rest import (
                QUOTA_EXHAUSTED_REPLY_FA,
                QUOTA_EXHAUSTED_REPLY_EN,
                is_quota_status_or_text,
            )
            if is_quota_status_or_text(None, exc_str):
                stream_err_msg = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            else:
                stream_err_msg = (
                    "مشکلی در پردازش درخواست پیش آمد قربان؛ اما تمام امکانات آفلاین گوشی در دسترس است."
                    if is_fa else
                    "I encountered a processing issue, sir. All offline device controls remain fully operational."
                )
            yield json.dumps({"type": "sentence", "text": stream_err_msg}) + "\n"
            yield json.dumps({"type": "actions", "client_actions": []}) + "\n"
            yield json.dumps({"type": "done"}) + "\n"

    return StreamingResponse(event_generator(), media_type="application/x-ndjson")


class VisionRequest(BaseModel):
    image_base64: str
    prompt: Optional[str] = None
    mode: Optional[str] = "camera"


@app.post("/api/vision")
async def vision_endpoint(req: VisionRequest, request: Request = None):
    """Analyze a single camera frame or screen capture using multimodal vision models with auto-fallback."""
    api_key = os.environ.get("OPENROUTER_API_KEY")
    if request is not None:
        auth = request.headers.get("authorization", "")
        if auth.startswith("Bearer "):
            bearer = auth[7:].strip()
            if bearer:
                api_key = bearer

    gemini_key = os.environ.get("GEMINI_API_KEY")

    cfg_status = get_config_status()
    if not api_key:
        api_key = cfg_status.get("openrouter_key")
    if not gemini_key:
        gemini_key = cfg_status.get("gemini_key")

    active_model = cfg_status.get("active_model")

    is_persian = any("\u0600" <= c <= "\u06FF" for c in (req.prompt or ""))
    default_prompt = (
        ("تصویر را با دقت توصیف کن و اگر متنی یا سوالی در آن است پاسخ بده." if is_persian else "Describe what you see in detail and answer any visible questions.")
        if req.mode == "camera"
        else ("محتوای روی صفحه را به دقت بررسی کن، متون و عناصر را بخوان و توضیح بده." if is_persian else "Analyze what is on the screen, read visible text, and explain clearly.")
    )
    user_prompt = (req.prompt or "").strip() or default_prompt

    try:
        from openjarvis.engine.cloud_rest import analyze_vision_multimodal
        raw_result = await analyze_vision_multimodal(
            image_base64=req.image_base64,
            prompt=user_prompt,
            api_key=api_key,
            active_model=active_model,
            gemini_key=gemini_key,
        )
    except Exception as ex:
        logger.warning("Vision analysis error: %s", ex)
        raw_result = "تحلیل تصویر مقدور نبود قربان." if is_persian else "Visual analysis was unable to complete, sir."
    clean_result = _clean_tts_response(str(raw_result or ""))

    _conversation_history.append({"role": "user", "content": f"[Vision: {req.mode}] {user_prompt}"})
    _conversation_history.append({"role": "assistant", "content": clean_result})

    return {
        "response": clean_result,
        "mode": req.mode,
        "prompt": user_prompt,
        "status": "success",
    }


class NotificationSummarizeRequest(BaseModel):
    notifications: List[Dict[str, Any]] = []
    language: Optional[str] = None


@app.post("/api/notifications/summarize")
async def summarize_notifications_endpoint(req: NotificationSummarizeRequest, request: Request = None):
    """Generate a 2-3 sentence executive briefing of active unread notifications."""
    notifs = req.notifications or []
    if not notifs:
        return {
            "summary": "You have no unread notifications, sir." if (req.language != "fa") else "هیچ اعلان جدیدی وجود ندارد قربان.",
            "count": 0,
            "status": "success",
        }

    formatted_items = []
    has_persian = False
    for n in notifs[:10]:
        app_name = n.get("app") or n.get("package") or "App"
        sender = n.get("sender") or "Unknown"
        text = (n.get("text") or "").strip()
        if any("\u0600" <= c <= "\u06FF" for c in (sender + text)):
            has_persian = True
        formatted_items.append(f"• [{app_name}] {sender}: {text}")

    is_fa = req.language == "fa" or (req.language is None and has_persian)
    prompt = (
        f"You are J.A.R.V.I.S., Tony Stark's executive AI assistant. "
        f"Summarize these {len(notifs)} unread notifications into a concise, polite 2-3 sentence briefing for Tony Stark. "
        f"Highlight important senders and actions needed. Do NOT use markdown asterisks or bullet points in the summary, speak naturally.\n\n"
        f"Notifications:\n" + "\n".join(formatted_items)
    )
    if is_fa:
        prompt += "\nRespond entirely in elegant, natural Persian, addressing me as قربان."
    else:
        prompt += "\nRespond in English, addressing me as sir."

    api_key = os.environ.get("OPENROUTER_API_KEY")
    if request is not None:
        auth = request.headers.get("authorization", "")
        if auth.startswith("Bearer "):
            bearer = auth[7:].strip()
            if bearer:
                api_key = bearer

    cfg_status = get_config_status()
    if not api_key:
        api_key = cfg_status.get("openrouter_key")
    gemini_key = cfg_status.get("gemini_key") or os.environ.get("GEMINI_API_KEY")
    active_model = cfg_status.get("active_model")

    summary = None
    if api_key or gemini_key:
        try:
            import httpx
            from openjarvis.engine.cloud_rest import generate_openai_compat_rest
            base_url = os.environ.get("OPENAI_BASE_URL") or "https://openrouter.ai/api/v1"
            with httpx.Client(timeout=15.0) as client:
                res = generate_openai_compat_rest(
                    client=client,
                    base_url=base_url,
                    api_key=api_key or gemini_key,
                    model=active_model or "google/gemini-2.0-flash-exp:free",
                    messages=[Message(role=Role.USER, content=prompt)],
                    max_tokens=300,
                )
                summary = str((res or {}).get("content") or "").strip()
        except Exception as e:
            logger.warning(f"Notification summary LLM call failed: {e}")

    if not summary:
        senders = list(dict.fromkeys(n.get("sender") for n in notifs[:3] if n.get("sender")))
        if is_fa:
            senders_str = "، ".join(senders) if senders else "مخاطبین"
            summary = f"قربان، شما {len(notifs)} اعلان جدید دارید؛ عمدتاً از طرف {senders_str}."
        else:
            senders_str = ", ".join(senders) if senders else "various senders"
            summary = f"You have {len(notifs)} unread notifications, sir, primarily from {senders_str}."

    clean_summary = _clean_tts_response(summary)
    return {
        "summary": clean_summary,
        "count": len(notifs),
        "status": "success",
    }


# ---------------------------------------------------------------------------
# Feature 1 & 2: Live Multimodal Sessions (Audio Listening & Screen Watching)
# ---------------------------------------------------------------------------

class AudioSessionStartRequest(BaseModel):
    session_id: Optional[str] = None
    media_metadata: Optional[Dict[str, Any]] = None


@app.post("/api/audio/session/start")
async def audio_session_start_endpoint(req: Optional[AudioSessionStartRequest] = None):
    """Start or initialize a real-time live audio listening session."""
    from openjarvis.live_media_session import LiveSessionStore
    store = LiveSessionStore.get_instance()
    sess_id = (req.session_id if req and req.session_id else None) or f"audio_{int(time.time() * 1000)}"
    meta = req.media_metadata if req else {}
    sess = store.get_or_create_audio_session(sess_id, media_metadata=meta)
    sess.status = "listening"
    store.save_audio_session(sess)
    return {
        "session_id": sess_id,
        "status": "listening",
        "created_at": sess.created_at,
    }


@app.post("/api/audio/session/chunk")
async def audio_session_chunk_endpoint(
    audio: UploadFile = File(...),
    session_id: str = Form(...),
    metadata: Optional[str] = Form(None),
):
    """Receive a rolling 10s audio chunk, transcribe multi-lingually, and append to session."""
    from openjarvis.live_media_session import LiveSessionStore, transcribe_audio_chunk
    store = LiveSessionStore.get_instance()
    meta_dict = None
    if metadata:
        try:
            meta_dict = json.loads(metadata)
        except Exception:
            pass

    sess = store.get_or_create_audio_session(session_id, media_metadata=meta_dict)
    wav_bytes = await audio.read()
    if not wav_bytes:
        return {"error": "Empty audio chunk", "session_id": session_id}

    if sess.sample_wav_bytes is None or len(sess.sample_wav_bytes) < 320000:
        if sess.sample_wav_bytes is None:
            sess.sample_wav_bytes = wav_bytes
        else:
            sess.sample_wav_bytes += wav_bytes

    sess.chunks_count += 1

    chunk_text, lang = await transcribe_audio_chunk(wav_bytes)
    if chunk_text:
        if sess.transcript:
            sess.transcript += " " + chunk_text
        else:
            sess.transcript = chunk_text
        sess.detected_language = lang

    store.save_audio_session(sess)

    return {
        "session_id": session_id,
        "chunk_index": sess.chunks_count,
        "chunk_transcript": chunk_text,
        "total_transcript": sess.transcript,
        "status": "listening",
    }


class AudioSessionFinishRequest(BaseModel):
    session_id: str
    question: Optional[str] = None
    language: Optional[str] = "fa"


@app.post("/api/audio/session/finish")
async def audio_session_finish_endpoint(req: AudioSessionFinishRequest):
    """Finalize audio listening, run multi-layer song ID, and optionally answer immediate question."""
    from openjarvis.live_media_session import LiveSessionStore, identify_song, answer_audio_session_query
    store = LiveSessionStore.get_instance()
    sess = store.get_audio_session(req.session_id)
    if not sess:
        return {"error": "Session not found", "session_id": req.session_id}

    sess.status = "finished"
    store.save_audio_session(sess)

    song = await identify_song(sess)

    answer = None
    if req.question:
        answer = await answer_audio_session_query(
            session=sess,
            question=req.question,
            user_language=req.language or "fa",
        )
        answer = _clean_tts_response(answer)

    return {
        "session_id": req.session_id,
        "status": "finished",
        "identified_song": song,
        "transcript": sess.transcript,
        "chunks_count": sess.chunks_count,
        "answer": answer,
    }


class AudioSessionAskRequest(BaseModel):
    session_id: Optional[str] = None
    question: str
    language: Optional[str] = "fa"


@app.post("/api/audio/session/ask")
async def audio_session_ask_endpoint(req: AudioSessionAskRequest):
    """Answer any question grounded in the captured audio session."""
    from openjarvis.live_media_session import LiveSessionStore, answer_audio_session_query
    store = LiveSessionStore.get_instance()
    sess = store.get_audio_session(req.session_id)
    if not sess:
        is_fa = req.language == "fa" or any("\u0600" <= c <= "\u06FF" for c in req.question)
        msg = "جلسه صوتی فعالی برای پاسخگویی یافت نشد قربان." if is_fa else "No active audio session found, sir."
        return {"response": msg, "session_id": req.session_id, "status": "error"}

    answer = await answer_audio_session_query(
        session=sess,
        question=req.question,
        user_language=req.language or "fa",
    )
    clean_answer = _clean_tts_response(answer)
    return {
        "session_id": sess.session_id,
        "response": clean_answer,
        "identified_song": sess.identified_song,
        "transcript": sess.transcript,
        "status": "success",
    }


class LiveVisionRequest(BaseModel):
    session_id: Optional[str] = None
    current_frame_base64: str
    recent_frames_base64: Optional[List[str]] = None
    ui_text_context: Optional[str] = None
    foreground_app: Optional[str] = None
    prompt: Optional[str] = None
    language: Optional[str] = "fa"


@app.post("/api/vision/live")
async def vision_live_endpoint(req: LiveVisionRequest, request: Request = None):
    """Continuous Live Screen Watching session visual reasoning with multi-turn memory."""
    from openjarvis.live_media_session import LiveSessionStore
    from openjarvis.engine.cloud_rest import analyze_vision_live_multimodal

    api_key = os.environ.get("OPENROUTER_API_KEY")
    if request is not None:
        auth = request.headers.get("authorization", "")
        if auth.startswith("Bearer "):
            bearer = auth[7:].strip()
            if bearer:
                api_key = bearer

    cfg_status = get_config_status()
    if not api_key:
        api_key = cfg_status.get("openrouter_key")
    gemini_key = cfg_status.get("gemini_key") or os.environ.get("GEMINI_API_KEY")
    active_model = cfg_status.get("active_model")

    store = LiveSessionStore.get_instance()
    sess_id = req.session_id or f"vis_{int(time.time() * 1000)}"
    v_sess = store.get_or_create_visual_session(
        session_id=sess_id,
        foreground_app=req.foreground_app or "",
        ui_text=req.ui_text_context or "",
    )

    is_persian = req.language == "fa" or any("\u0600" <= c <= "\u06FF" for c in (req.prompt or ""))
    default_prompt = (
        "محتوای زنده روی صفحه گوشی را تحلیل کن، عناصر و متون را بررسی کن و پاسخ بده."
        if is_persian
        else "Analyze the live screen view, examine on-screen elements and text, and answer clearly."
    )
    user_prompt = (req.prompt or "").strip() or default_prompt

    # Optional Web Verification Hook
    web_context = ""
    lower_p = user_prompt.lower()
    if any(k in lower_p for k in ["قیمت", "چنده", "امتیاز", "تاریخ", "فیلم", "بازیگر", "بازی", "price", "rating", "who is", "release date"]):
        try:
            from openjarvis.web_intel import web_search
            search_query = f"{req.foreground_app or ''} {user_prompt[:60]}".strip()
            web_res = await web_search(search_query, max_results=3)
            if web_res:
                web_lines = "\n".join(f"- {r.get('title')}: {r.get('snippet')}" for r in web_res[:3])
                web_context = f"\n[LIVE WEB INTEL VERIFICATION]:\n{web_lines}\n"
        except Exception as e:
            logger.debug("Live vision web verification notice: %s", e)

    full_prompt = f"{user_prompt}{web_context}" if web_context else user_prompt

    try:
        raw_result = await analyze_vision_live_multimodal(
            current_frame_base64=req.current_frame_base64,
            recent_frames_base64=req.recent_frames_base64,
            prompt=full_prompt,
            ui_text_context=req.ui_text_context,
            foreground_app=req.foreground_app,
            conversation_history=v_sess.history,
            api_key=api_key,
            active_model=active_model,
            gemini_key=gemini_key,
        )
    except Exception as ex:
        logger.warning("Live vision analysis exception: %s", ex)
        raw_result = "قربان، در حال حاضر امکان تحلیل زنده صفحه مقدور نبود." if is_persian else "Live screen analysis could not complete, sir."

    clean_result = _clean_tts_response(str(raw_result or ""))

    # Update visual session history
    v_sess.history.append({"role": "user", "content": user_prompt})
    v_sess.history.append({"role": "assistant", "content": clean_result})
    store.save_visual_session(v_sess)

    return {
        "session_id": sess_id,
        "response": clean_result,
        "prompt": user_prompt,
        "foreground_app": req.foreground_app,
        "status": "success",
    }


# ---------------------------------------------------------------------------
# Iron Man Anti-Theft & Security Evidence Vault Endpoints
# ---------------------------------------------------------------------------

class SecurityLocationRequest(BaseModel):
    latitude: float
    longitude: float
    accuracy: Optional[float] = None
    speed: Optional[float] = None
    battery: Optional[int] = None
    device_id: Optional[str] = None


class SecurityTelegramConfigRequest(BaseModel):
    bot_token: str
    chat_id: str
    enabled: Optional[bool] = True
    test: Optional[bool] = False


class SecurityCommandRequest(BaseModel):
    command: str
    parameters: Optional[Dict[str, Any]] = None


@app.post("/api/security/evidence")
async def security_evidence_endpoint(
    file: UploadFile = File(...),
    evidence_type: str = Form("audio"),
    session_id: Optional[str] = Form(None),
    battery: Optional[int] = Form(None),
    latitude: Optional[float] = Form(None),
    longitude: Optional[float] = Form(None),
):
    """Store silent photo or 5s rolling audio evidence and forward to Telegram."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    file_bytes = await file.read()
    metadata = {
        "session_id": session_id,
        "battery": battery,
        "latitude": latitude,
        "longitude": longitude,
        "received_at": datetime.now(timezone.utc).isoformat(),
    }
    result = vault.save_evidence(
        file_bytes=file_bytes,
        filename=file.filename or f"evidence_{int(time.time())}",
        evidence_type=evidence_type,
        metadata=metadata,
    )
    return result


@app.post("/api/security/location")
async def security_location_endpoint(req: SecurityLocationRequest):
    """Record location breadcrumb and forward pin + map link to Telegram."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    result = vault.save_location(req.model_dump())
    return result


@app.post("/api/security/telegram/config")
async def security_telegram_config_endpoint(req: SecurityTelegramConfigRequest):
    """Save Telegram bot token & chat_id, and optionally trigger test ping."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    status = vault.save_telegram_config(
        bot_token=req.bot_token,
        chat_id=req.chat_id,
        enabled=req.enabled if req.enabled is not None else True,
    )
    if req.test:
        test_res = await vault.test_telegram_connection()
        status["test_result"] = test_res
    if req.bot_token and req.chat_id:
        try:
            vault.start_polling_loop()
        except Exception:
            pass
    return status


@app.get("/api/security/telegram/status")
async def security_telegram_status_endpoint():
    """Get Telegram alert forwarder status."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    return vault.get_telegram_status()


@app.get("/api/security/commands")
async def security_get_commands_endpoint(pop: bool = True):
    """Fetch pending remote security commands dispatched via Telegram bot."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    if pop:
        cmds = vault.pop_pending_commands()
    else:
        cmds = vault.peek_pending_commands()
    return {"commands": cmds, "count": len(cmds)}


@app.post("/api/security/command")
async def security_dispatch_command_endpoint(req: SecurityCommandRequest):
    """Queue a security command for the client device."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    cmd_obj = vault.add_pending_command(req.command, req.parameters)
    return {"status": "queued", "command": cmd_obj}


@app.get("/api/security/status")
async def security_status_endpoint():
    """Summary of security vault."""
    from openjarvis.security_vault import SecurityEvidenceVault
    vault = SecurityEvidenceVault.get_instance()
    return {
        "status": "active",
        "vault_dir": vault.base_dir,
        "telegram": vault.get_telegram_status(),
        "pending_commands_count": len(vault.peek_pending_commands()),
    }


# ---------------------------------------------------------------------------
# Part A & C: Long-Term Memory and Safe VPS Operations Endpoints
# ---------------------------------------------------------------------------

class SaveMemoryRequest(BaseModel):
    content: str
    category: Optional[str] = "general"


@app.post("/api/memory")
async def save_memory_endpoint(req: SaveMemoryRequest):
    """Save persistent long-term memory."""
    from openjarvis.memory_store import get_memory_store
    mem = get_memory_store().remember(
        content=req.content,
        category=req.category or "general"
    )
    return {"id": mem.get("id"), "content": req.content, "status": "saved", "memory": mem}


@app.get("/api/memory")
async def list_memories_endpoint(limit: int = 100):
    """List persistent long-term memories."""
    from openjarvis.memory_store import get_memory_store
    mems = get_memory_store().list_memories(limit=limit)
    return {"memories": mems, "count": len(mems), "status": "success"}


@app.delete("/api/memory/{memory_id}")
async def delete_memory_endpoint(memory_id: int):
    """Delete a specific stored memory by ID."""
    from openjarvis.memory_store import get_memory_store
    ok = get_memory_store().delete_memory(memory_id)
    return {"id": memory_id, "deleted": ok, "status": "success" if ok else "not_found"}


@app.delete("/api/memory")
async def clear_all_memories_endpoint():
    """Clear all stored long-term memories."""
    from openjarvis.memory_store import get_memory_store
    cnt = get_memory_store().clear_all_memories()
    return {"count": cnt, "status": "cleared"}


@app.get("/api/vps/status")
async def vps_status_endpoint():
    """Retrieve VPS systems telemetry and whitelisted service health."""
    from openjarvis.vps_ops import vps_status
    return vps_status()


class VpsRestartRequest(BaseModel):
    service: str = "openjarvis"


@app.post("/api/vps/restart")
async def vps_restart_endpoint(req: VpsRestartRequest):
    """Safely restart whitelisted VPS daemon services."""
    from openjarvis.vps_ops import vps_restart_service
    return vps_restart_service(req.service)


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


def _speech_bandpass_filter(shorts: tuple[int, ...] | list[int], framerate: int = 16000) -> list[int]:
    """Apply gentle speech-band filter (300Hz-3400Hz) to attenuate bass rumble and high hiss."""
    if not shorts or framerate < 8000:
        return list(shorts)
    import math
    dt = 1.0 / framerate
    rc_hp = 1.0 / (2.0 * math.pi * 300.0)
    alpha_hp = rc_hp / (rc_hp + dt)
    rc_lp = 1.0 / (2.0 * math.pi * 3400.0)
    alpha_lp = dt / (rc_lp + dt)

    out = []
    prev_hp_x = 0.0
    prev_hp_y = 0.0
    prev_lp_y = 0.0
    for s in shorts:
        x = float(s)
        y_hp = alpha_hp * (prev_hp_y + x - prev_hp_x)
        prev_hp_x = x
        prev_hp_y = y_hp
        y_lp = prev_lp_y + alpha_lp * (y_hp - prev_lp_y)
        prev_lp_y = y_lp
        out.append(max(-32768, min(32767, int(y_lp))))
    return out


def _normalize_audio(wav_bytes: bytes) -> tuple[bytes, float, float]:
    """Calculate duration, RMS, and return bandpass-filtered and peak-normalized WAV (Fix 1 & Fix 2)."""
    try:
        import wave, io, math, struct
        try:
            import audioop
        except ImportError:
            audioop = None

        with wave.open(io.BytesIO(wav_bytes), "rb") as w:
            params = w.getparams()
            nchannels, sampwidth, framerate, nframes = params[:4]
            frames = w.readframes(nframes)
        duration = nframes / float(framerate) if framerate > 0 else 0.0
        if not frames or sampwidth != 2:
            return wav_bytes, duration, 0.0

        count = len(frames) // 2
        raw_shorts = struct.unpack(f"<{count}h", frames[:count * 2])
        if count == 0:
            return wav_bytes, duration, 0.0

        # Apply 300Hz-3400Hz speech-band BPF to remove rumble and high hiss
        shorts = _speech_bandpass_filter(raw_shorts, framerate)

        rms = math.sqrt(sum(s * s for s in shorts) / count)
        peak = max(abs(s) for s in shorts) if shorts else 0

        # Peak normalization: if 80 < peak < 18000, boost linearly to ~24000 (-2.5 dBFS)
        gain = min(15.0, 24000.0 / peak) if 80 < peak < 18000 else 1.0
        clamped = [max(-32768, min(32767, int(s * gain))) for s in shorts]
        norm_frames = struct.pack(f"<{count}h", *clamped)
        norm_rms = math.sqrt(sum(s * s for s in clamped) / count)
        out_buf = io.BytesIO()
        with wave.open(out_buf, "wb") as out_w:
            out_w.setparams(params)
            out_w.writeframes(norm_frames)
        if gain > 1.01:
            logger.info("Server audio normalized: peak %d -> 24000, rms %.1f -> %.1f (gain=%.2f)", peak, rms, norm_rms, gain)
        return out_buf.getvalue(), duration, norm_rms
    except Exception as e:
        logger.debug("Audio normalization skipped: %s", e)
        return wav_bytes, 0.0, 0.0


def _do_transcribe(data: bytes, requested_lang: str = "auto") -> str:
    """Transcribe audio WAV/PCM bytes with smart concurrent bilingual (fa-IR + en-US) recognition."""
    norm_data, duration, rms = _normalize_audio(data)
    try:
        import io
        import speech_recognition as sr
        from concurrent.futures import ThreadPoolExecutor

        r = sr.Recognizer()
        with sr.AudioFile(io.BytesIO(norm_data)) as source:
            audio = r.record(source)

        def _recognize(lang: str) -> str:
            try:
                res = r.recognize_google(audio, language=lang)
                return (res or "").strip()
            except (sr.UnknownValueError, Exception):
                return ""

        wake_words_fa = (
            "جارویس", "جرویس", "جارویز", "جرویز", "چارویس", "چرویس",
            "جاروویس", "جاربیس", "دارویس", "یارویس", "جارو بیس"
        )
        wake_words_en = (
            "jarvis", "hey jarvis", "hi jarvis", "ok jarvis", "jervis",
            "javis", "garvis", "charvis", "jarvish", "jarvez", "travis"
        )

        req_clean = (requested_lang or "auto").strip().lower()
        result = ""
        if req_clean in ("fa", "fa-ir"):
            txt_fa = _recognize("fa-IR")
            if txt_fa:
                result = txt_fa
            else:
                result = _recognize("en-US")
        elif req_clean in ("en", "en-us"):
            txt_en = _recognize("en-US")
            if txt_en:
                result = txt_en
            else:
                result = _recognize("fa-IR")
        else:
            # "auto" mode: execute fa-IR and en-US concurrently in parallel
            with ThreadPoolExecutor(max_workers=3) as executor:
                fut_fa = executor.submit(_recognize, "fa-IR")
                fut_en = executor.submit(_recognize, "en-US")
                txt_fa = fut_fa.result()
                txt_en = fut_en.result()

            # Smart wake-word & bilingual selection:
            # 1. Prefer candidate containing a wake-word variant
            fa_has_wake = any(w in txt_fa.lower() for w in wake_words_fa)
            en_has_wake = any(w in txt_en.lower() for w in wake_words_en)
            has_fa_chars = any("\u0600" <= c <= "\u06FF" for c in txt_fa)

            if fa_has_wake:
                result = txt_fa
            elif en_has_wake:
                result = txt_en
            elif txt_fa and has_fa_chars:
                result = txt_fa
            elif txt_en:
                result = txt_en
            elif txt_fa:
                result = txt_fa

            # Fallback to en-GB if both return empty on valid audio (RMS > 150) (Fix 2: 4)
            if not result and rms > 150.0:
                txt_gb = _recognize("en-GB")
                if txt_gb:
                    result = txt_gb

        # Fallback to local whisper or speech backend if configured
        if not result:
            try:
                txt = r.recognize_whisper(audio)
                if txt and txt.strip():
                    result = txt.strip()
            except Exception as e:
                logger.debug("Whisper fallback transcription error: %s", e)
        if not result:
            try:
                from openjarvis.core.config import load_config
                from openjarvis.speech._discovery import discover_speech_backend
                cfg = load_config()
                backend = discover_speech_backend(cfg)
                if backend:
                    res = backend.transcribe(norm_data)
                    if res and res.strip():
                        result = res.strip()
            except Exception as e:
                logger.debug("SpeechBackend fallback error: %s", e)

        logger.info(
            "STT audio: bytes=%d, duration=%.2fs, rms=%.1f -> transcript='%s'",
            len(data), duration, rms, result
        )
        return result
    except Exception as exc:
        logger.warning("VAD transcription exception: %s", exc)
    return ""


@app.post("/api/stt")
async def stt_endpoint(request: Request):
    """Transcribe raw audio WAV or multipart form data to text with bilingual support."""
    content_type = request.headers.get("content-type", "")
    req_lang = request.query_params.get("lang") or request.query_params.get("language") or "auto"
    audio_bytes = b""
    if "multipart/form-data" in content_type:
        form = await request.form()
        audio_file = form.get("audio") or form.get("file")
        if audio_file and hasattr(audio_file, "read"):
            audio_bytes = await audio_file.read()
        lang_form = form.get("lang") or form.get("language")
        if lang_form:
            req_lang = str(lang_form).strip()
    elif "application/json" in content_type:
        try:
            body_json = await request.json()
            b64_str = body_json.get("audio_base64") or body_json.get("audio") or body_json.get("data") or ""
            if b64_str:
                audio_bytes = base64.b64decode(b64_str)
            lang_json = body_json.get("lang") or body_json.get("language")
            if lang_json:
                req_lang = str(lang_json).strip()
        except Exception:
            pass
    else:
        audio_bytes = await request.body()

    if not audio_bytes or len(audio_bytes) < 4:
        return {"success": False, "transcript": "", "text": "", "language": "en", "error": "Received empty or invalid audio data"}

    try:
        transcript = await asyncio.to_thread(_do_transcribe, audio_bytes, req_lang)
    except TypeError:
        transcript = await asyncio.to_thread(_do_transcribe, audio_bytes)
    transcript = (transcript or "").strip()
    is_fa = any("\u0600" <= c <= "\u06FF" for c in transcript)
    err = None if transcript else "No speech detected in audio"
    return {
        "success": bool(transcript),
        "transcript": transcript,
        "text": transcript,
        "language": "fa" if is_fa else "en",
        "error": err,
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
    req_lang = request.query_params.get("lang") or "auto"
    if "multipart/form-data" in content_type:
        form = await request.form()
        audio_file = form.get("audio")
        if audio_file and hasattr(audio_file, "read"):
            audio_bytes = await audio_file.read()
        else:
            audio_bytes = b""
        tts_val = form.get("tts", "true")
        tts_requested = str(tts_val).lower() in ("true", "1", "yes")
        lang_form = form.get("lang") or form.get("language")
        if lang_form:
            req_lang = str(lang_form).strip()
    else:
        audio_bytes = await request.body()
        tts_val = request.query_params.get("tts", "true")
        tts_requested = str(tts_val).lower() in ("true", "1", "yes")

    if not audio_bytes or len(audio_bytes) < 44:
        return {"error": "Received empty or invalid WAV audio data", "status": "ignored"}

    # Direct command bypass flag (from Tap-to-Talk or pre-stripped client wake word)
    is_direct = (
        request.query_params.get("direct", "false").lower() in ("true", "1", "yes")
        or request.query_params.get("tap_to_talk", "false").lower() in ("true", "1", "yes")
        or request.headers.get("x-direct-command", "false").lower() in ("true", "1", "yes")
    )

    try:
        transcript = await asyncio.to_thread(_do_transcribe, audio_bytes, req_lang)
    except TypeError:
        transcript = await asyncio.to_thread(_do_transcribe, audio_bytes)
    transcript = transcript.strip()
    if not transcript:
        return {
            "status": "no_speech",
            "transcription": "",
            "message": "No speech detected in audio utterance.",
        }

    logger.info("VAD Utterance transcribed: '%s' (Session state: %s, Direct: %s)", transcript, _voice_session.get("state"), is_direct)

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
        r"^(?:(?:hey|hi|ok|okay|dear|hello)\s+)?(?:jarvis\s+jarvis|jarvis|javis|service|harvey|travis|چاویس|جارویس|جارویز|جرویس|سرویس|هی\s*جارویس|سلام\s*جارویس)(?:[,\s]+(.*))?$",
        re.IGNORECASE,
    )
    m_wake = wake_regex.match(transcript)

    command_to_run = None
    if is_direct:
        # Direct command: bypass wake word check completely!
        if m_wake:
            command_to_run = (m_wake.group(1) or "").strip() or transcript
        else:
            command_to_run = transcript
        _voice_session["state"] = "STANDBY"
        _voice_session["awake_until"] = 0.0
    elif m_wake:
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

    import aiohttp
    import edge_tts

    last_exc = None
    for attempt in range(2):
        try:
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
            last_exc = exc
            logger.warning("edge-tts synthesis attempt %d failed: %s", attempt + 1, exc)
            if attempt == 0:
                await asyncio.sleep(0.2)

    raise HTTPException(status_code=503, detail=f"TTS synthesis unavailable: {last_exc}")


@app.post("/api/tts")
async def tts_endpoint(req: TTSRequest):
    """Synthesize speech using edge-tts (British RyanNeural or Persian FaridNeural)."""
    text = (req.text or "").strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    return await _synthesize_edge_tts(text, req.voice, req.rate, req.pitch)


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
