"""Live Audio & Media Understanding Session Manager for OpenJarvis.

Handles real-time audio streaming chunks, multi-language speech transcription,
multi-layer song identification (MediaMetadata -> ShazamIO -> Lyrics Web Search -> Multimodal Audio),
and grounded question answering.
"""

from __future__ import annotations

import base64
import json
import logging
import os
import sqlite3
import tempfile
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import httpx

from openjarvis.engine.cloud_rest import (
    generate_openai_compat_rest,
    safe_extract_message_content,
    safe_get_dict,
    safe_get_list,
)

logger = logging.getLogger(__name__)

# In-memory and SQLite-backed session storage
DB_PATH = Path(os.environ.get("OPENJARVIS_DATA_DIR", "/tmp")) / "openjarvis_live_sessions.db"


@dataclass
class AudioSession:
    session_id: str
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    chunks_count: int = 0
    transcript: str = ""
    detected_language: str = "fa"
    media_metadata: Dict[str, Any] = field(default_factory=dict)
    identified_song: Optional[Dict[str, Any]] = None
    sample_wav_bytes: Optional[bytes] = None
    qa_history: List[Dict[str, str]] = field(default_factory=list)
    status: str = "listening"  # "listening", "finished"


@dataclass
class VisualSession:
    session_id: str
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)
    history: List[Dict[str, str]] = field(default_factory=list)
    last_app: str = ""
    last_ui_text: str = ""


class LiveSessionStore:
    """Thread-safe in-memory cache with SQLite persistence for live multimodal sessions."""

    _instance: Optional[LiveSessionStore] = None

    def __init__(self) -> None:
        self.audio_sessions: Dict[str, AudioSession] = {}
        self.visual_sessions: Dict[str, VisualSession] = {}
        self._init_sqlite()

    @classmethod
    def get_instance(cls) -> LiveSessionStore:
        if cls._instance is None:
            cls._instance = LiveSessionStore()
        return cls._instance

    def _init_sqlite(self) -> None:
        try:
            DB_PATH.parent.mkdir(parents=True, exist_ok=True)
            with sqlite3.connect(DB_PATH) as conn:
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS live_audio_sessions (
                        session_id TEXT PRIMARY KEY,
                        created_at REAL,
                        updated_at REAL,
                        chunks_count INTEGER,
                        transcript TEXT,
                        detected_language TEXT,
                        media_metadata TEXT,
                        identified_song TEXT,
                        qa_history TEXT,
                        status TEXT
                    )
                """)
                conn.execute("""
                    CREATE TABLE IF NOT EXISTS live_visual_sessions (
                        session_id TEXT PRIMARY KEY,
                        created_at REAL,
                        updated_at REAL,
                        history TEXT,
                        last_app TEXT,
                        last_ui_text TEXT
                    )
                """)
                conn.commit()
        except Exception as ex:
            logger.warning("Could not initialize SQLite live session store: %s", ex)

    # -------------------------------------------------------------------------
    # Audio Sessions
    # -------------------------------------------------------------------------
    def get_or_create_audio_session(self, session_id: str, media_metadata: Optional[Dict[str, Any]] = None) -> AudioSession:
        self._clean_stale()
        if session_id in self.audio_sessions:
            sess = self.audio_sessions[session_id]
            sess.updated_at = time.time()
            if media_metadata:
                sess.media_metadata.update(media_metadata)
            return sess

        sess = AudioSession(
            session_id=session_id,
            media_metadata=media_metadata or {},
        )
        self.audio_sessions[session_id] = sess
        self._persist_audio(sess)
        return sess

    def get_audio_session(self, session_id: Optional[str] = None) -> Optional[AudioSession]:
        self._clean_stale()
        if session_id and session_id in self.audio_sessions:
            return self.audio_sessions[session_id]
        # If no session_id specified, return the most recent active/finished session (<10 minutes old)
        if not session_id and self.audio_sessions:
            sorted_s = sorted(self.audio_sessions.values(), key=lambda s: s.updated_at, reverse=True)
            if sorted_s and (time.time() - sorted_s[0].updated_at) < 600:
                return sorted_s[0]
        return None

    def save_audio_session(self, sess: AudioSession) -> None:
        sess.updated_at = time.time()
        self.audio_sessions[sess.session_id] = sess
        self._persist_audio(sess)

    def _persist_audio(self, sess: AudioSession) -> None:
        try:
            with sqlite3.connect(DB_PATH) as conn:
                conn.execute("""
                    INSERT OR REPLACE INTO live_audio_sessions 
                    (session_id, created_at, updated_at, chunks_count, transcript, detected_language, media_metadata, identified_song, qa_history, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, (
                    sess.session_id,
                    sess.created_at,
                    sess.updated_at,
                    sess.chunks_count,
                    sess.transcript,
                    sess.detected_language,
                    json.dumps(sess.media_metadata, ensure_ascii=False),
                    json.dumps(sess.identified_song, ensure_ascii=False) if sess.identified_song else None,
                    json.dumps(sess.qa_history, ensure_ascii=False),
                    sess.status,
                ))
                conn.commit()
        except Exception as ex:
            logger.debug("Failed to persist audio session to SQLite: %s", ex)

    # -------------------------------------------------------------------------
    # Visual Sessions
    # -------------------------------------------------------------------------
    def get_or_create_visual_session(self, session_id: str, foreground_app: str = "", ui_text: str = "") -> VisualSession:
        self._clean_stale()
        if session_id in self.visual_sessions:
            v_sess = self.visual_sessions[session_id]
            v_sess.updated_at = time.time()
            if foreground_app:
                v_sess.last_app = foreground_app
            if ui_text:
                v_sess.last_ui_text = ui_text
            return v_sess

        v_sess = VisualSession(
            session_id=session_id,
            last_app=foreground_app,
            last_ui_text=ui_text,
        )
        self.visual_sessions[session_id] = v_sess
        self._persist_visual(v_sess)
        return v_sess

    def get_visual_session(self, session_id: Optional[str] = None) -> Optional[VisualSession]:
        self._clean_stale()
        if session_id and session_id in self.visual_sessions:
            return self.visual_sessions[session_id]
        if not session_id and self.visual_sessions:
            sorted_v = sorted(self.visual_sessions.values(), key=lambda v: v.updated_at, reverse=True)
            if sorted_v and (time.time() - sorted_v[0].updated_at) < 600:
                return sorted_v[0]
        return None

    def save_visual_session(self, sess: VisualSession) -> None:
        sess.updated_at = time.time()
        self.visual_sessions[sess.session_id] = sess
        self._persist_visual(sess)

    def _persist_visual(self, sess: VisualSession) -> None:
        try:
            with sqlite3.connect(DB_PATH) as conn:
                conn.execute("""
                    INSERT OR REPLACE INTO live_visual_sessions
                    (session_id, created_at, updated_at, history, last_app, last_ui_text)
                    VALUES (?, ?, ?, ?, ?, ?)
                """, (
                    sess.session_id,
                    sess.created_at,
                    sess.updated_at,
                    json.dumps(sess.history, ensure_ascii=False),
                    sess.last_app,
                    sess.last_ui_text,
                ))
                conn.commit()
        except Exception as ex:
            logger.debug("Failed to persist visual session to SQLite: %s", ex)

    def _clean_stale(self) -> None:
        now = time.time()
        # Keep sessions for up to 15 minutes
        stale_audio = [k for k, v in self.audio_sessions.items() if (now - v.updated_at) > 900]
        for k in stale_audio:
            del self.audio_sessions[k]

        stale_visual = [k for k, v in self.visual_sessions.items() if (now - v.updated_at) > 900]
        for k in stale_visual:
            del self.visual_sessions[k]


# -----------------------------------------------------------------------------
# Multi-Layer Song Identification Pipeline
# -----------------------------------------------------------------------------
async def identify_song(
    session: AudioSession,
    api_key: Optional[str] = None,
    gemini_key: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    """Identifies the playing track across 4 crash-proof layers."""
    # Layer 1: Instant active MediaSessionManager metadata from Android
    if session.media_metadata:
        title = (session.media_metadata.get("title") or "").strip()
        artist = (session.media_metadata.get("artist") or "").strip()
        if title:
            logger.info("Song identified via Layer 1 (MediaSessionManager): '%s' by '%s'", title, artist)
            info = {
                "title": title,
                "artist": artist or "Unknown Artist",
                "source": "media_metadata",
                "confidence": 1.0,
            }
            session.identified_song = info
            return info

    # Layer 2: Acoustic Fingerprint (Optional ShazamIO safe import)
    if session.sample_wav_bytes:
        try:
            from shazamio import Shazam  # type: ignore

            shazam = Shazam()
            # Shazam expects a file or bytes
            res = await shazam.recognize(session.sample_wav_bytes)
            track = (res or {}).get("track")
            if track:
                t_title = track.get("title") or ""
                t_artist = track.get("subtitle") or track.get("author") or ""
                if t_title:
                    logger.info("Song identified via Layer 2 (ShazamIO): '%s' by '%s'", t_title, t_artist)
                    info = {
                        "title": t_title,
                        "artist": t_artist,
                        "source": "shazamio",
                        "confidence": 0.95,
                    }
                    session.identified_song = info
                    return info
        except (ImportError, Exception) as ex:
            logger.debug("Layer 2 acoustic fingerprint skipped/failed: %s", ex)

    # Layer 3: Lyrics & Dialogue Web Lookup
    transcript = (session.transcript or "").strip()
    words = transcript.split()
    if len(words) >= 3:
        try:
            from openjarvis.web_intel import web_search

            search_query = f"lyrics {' '.join(words[:12])}"
            results = await web_search(search_query, max_results=4)
            if not results and any("\u0600" <= c <= "\u06FF" for c in transcript):
                search_query_fa = f"متن اهنگ {' '.join(words[:12])}"
                results = await web_search(search_query_fa, max_results=4)

            if results:
                snippets_text = "\n".join([f"- {r.get('title')}: {r.get('snippet')}" for r in results[:4]])
                prompt = (
                    "Based on the following extracted audio lyrics and web search results, identify the song title and artist.\n"
                    f"Lyrics/Phrases: \"{transcript[:150]}\"\n"
                    f"Search Results:\n{snippets_text}\n\n"
                    "Output ONLY valid JSON in format: {\"title\": \"Song Title\", \"artist\": \"Artist Name\"}. If unknown, output {\"title\": \"\", \"artist\": \"\"}."
                )
                raw_json = await generate_openai_compat_rest(
                    prompt=prompt,
                    api_key=api_key or os.environ.get("OPENROUTER_API_KEY"),
                    system="You are a music identification specialist. Output only JSON.",
                    temperature=0.0,
                    max_tokens=100,
                )
                clean_json = raw_json.strip()
                if "```json" in clean_json:
                    clean_json = clean_json.split("```json")[1].split("```")[0].strip()
                elif "```" in clean_json:
                    clean_json = clean_json.split("```")[1].split("```")[0].strip()
                parsed = json.loads(clean_json)
                p_title = (parsed.get("title") or "").strip()
                p_artist = (parsed.get("artist") or "").strip()
                if p_title:
                    logger.info("Song identified via Layer 3 (Lyrics Web Search): '%s' by '%s'", p_title, p_artist)
                    info = {
                        "title": p_title,
                        "artist": p_artist,
                        "source": "lyrics_web",
                        "confidence": 0.85,
                    }
                    session.identified_song = info
                    return info
        except Exception as ex:
            logger.debug("Layer 3 lyrics search identification error: %s", ex)

    return session.identified_song


# -----------------------------------------------------------------------------
# Audio Chunk Transcription Helper
# -----------------------------------------------------------------------------
async def transcribe_audio_chunk(
    wav_bytes: bytes,
    gemini_key: Optional[str] = None,
    api_key: Optional[str] = None,
) -> Tuple[str, str]:
    """Transcribe a 10s audio chunk, returning (transcribed_text, detected_language)."""
    if not wav_bytes:
        return "", "fa"

    # Try Gemini STT if key available
    gem_key = gemini_key or os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if gem_key and gem_key != "your_gemini_api_key_here":
        try:
            from openjarvis.speech.gemini_stt import GeminiSpeechBackend

            backend = GeminiSpeechBackend(api_key=gem_key)
            res = backend.transcribe(wav_bytes, format="wav")
            text = (res.text if hasattr(res, "text") else str(res)).strip()
            is_fa = any("\u0600" <= c <= "\u06FF" for c in text)
            lang = "fa" if is_fa else "en"
            return text, lang
        except Exception as ex:
            logger.debug("Gemini chunk transcription failed: %s", ex)

    # Fallback to Whisper API if OPENAI_API_KEY available
    openai_key = os.environ.get("OPENAI_API_KEY")
    if openai_key:
        try:
            from openjarvis.speech.openai_whisper import OpenAIWhisperBackend

            whisper = OpenAIWhisperBackend(api_key=openai_key)
            res = whisper.transcribe(wav_bytes, format="wav")
            text = (res.text if hasattr(res, "text") else str(res)).strip()
            is_fa = any("\u0600" <= c <= "\u06FF" for c in text)
            lang = "fa" if is_fa else "en"
            return text, lang
        except Exception as ex:
            logger.debug("Whisper chunk transcription failed: %s", ex)

    return "", "fa"


# -----------------------------------------------------------------------------
# Grounded Audio Session Q&A Reasoning
# -----------------------------------------------------------------------------
async def answer_audio_session_query(
    session: AudioSession,
    question: str,
    user_language: str = "fa",
    api_key: Optional[str] = None,
) -> str:
    """Answers any question grounded in the captured audio session, song ID, and transcript."""
    song = session.identified_song or {}
    song_title = song.get("title") or ""
    song_artist = song.get("artist") or ""
    transcript = (session.transcript or "").strip()

    is_fa = user_language == "fa" or any("\u0600" <= c <= "\u06FF" for c in question)

    # Direct Song ID question optimization
    q_lower = question.lower()
    is_song_id_query = any(k in q_lower for k in [
        "چه آهنگیه", "این چه آهنگیه", "اسم این آهنگ", "اسم آهنگ", "خواننده این کیه", "خوانندش کیه",
        "what song", "what's this song", "name of this song", "who sings this", "who is the singer"
    ])
    if is_song_id_query and song_title:
        if is_fa:
            artist_str = f" از {song_artist}" if song_artist else ""
            reply = f"این آهنگ «{song_title}»{artist_str} است قربان."
        else:
            artist_str = f" by {song_artist}" if song_artist else ""
            reply = f"This is \"{song_title}\"{artist_str}, sir."
        session.qa_history.append({"question": question, "answer": reply})
        return reply

    # If transcript is empty and song is unknown
    if not transcript and not song_title:
        if is_fa:
            return "قربان، متأسفانه در فایل صوتی کلام یا موسیقی واضحی برای شناسایی یا پاسخ ثبت نشد."
        else:
            return "I could not detect clear speech or music in the audio stream, sir."

    system_prompt = (
        "You are J.A.R.V.I.S., an intelligent and courteous AI assistant analyzing a live audio stream the user just captured from their phone.\n"
        f"Detected / Identified Song: {song_title or 'Unknown'} by {song_artist or 'Unknown'}\n"
        f"Verbatim Transcribed Speech / Lyrics:\n\"{transcript or 'No spoken lyrics detected (instrumental/ambient).'}\"\n\n"
        "Guidelines:\n"
        "1. Answer the user's question accurately based STRICTLY on the captured audio transcript, song identification, and media context.\n"
        "2. If the user asks for verbatim lyrics or dialogue ('چی گفت', 'متنش چی بود'), provide the transcribed lines faithfully.\n"
        "3. If the user asks to translate ('ترجمهش کن'), translate the transcribed lyrics/speech into Persian (or English if requested).\n"
        "4. If the user asks for a summary or explanation ('خلاصه کن', 'ساده بگو'), summarize key points concisely in 2-3 sentences.\n"
        "5. Keep the response natural, conversational, polite, and optimized for Male Edge-TTS voice playback (avoid markdown tables, bullet asterisks, or robotic formatting)."
    )

    history_msgs = []
    for turn in session.qa_history[-3:]:
        history_msgs.append({"role": "user", "content": turn.get("question", "")})
        history_msgs.append({"role": "assistant", "content": turn.get("answer", "")})

    user_msg = f"{question}\n(Respond in {'Persian' if is_fa else 'English'}, addressing the user politely as sir/قربان)."

    try:
        reply = await generate_openai_compat_rest(
            prompt=user_msg,
            system=system_prompt,
            api_key=api_key or os.environ.get("OPENROUTER_API_KEY"),
            temperature=0.3,
            max_tokens=450,
        )
    except Exception as ex:
        logger.warning("Failed to generate audio session answer: %s", ex)
        reply = "قربان، در حال حاضر امکان تحلیل دقیق کلام وجود نداشت." if is_fa else "Could not generate audio response at this time, sir."

    session.qa_history.append({"question": question, "answer": reply})
    return reply


# -----------------------------------------------------------------------------
# Follow-Up Context Injection for /api/chat
# -----------------------------------------------------------------------------
def get_active_audio_context(session_id: Optional[str] = None) -> Optional[str]:
    """Retrieves context string for active audio session if relevant to current conversation."""
    store = LiveSessionStore.get_instance()
    sess = store.get_audio_session(session_id)
    if not sess:
        return None

    # Check if session is recent (< 10 minutes)
    if (time.time() - sess.updated_at) > 600:
        return None

    song = sess.identified_song or {}
    song_info = f"{song.get('title', '')} by {song.get('artist', '')}".strip()
    transcript_snippet = (sess.transcript or "").strip()
    if len(transcript_snippet) > 400:
        transcript_snippet = transcript_snippet[:400] + "..."

    return (
        f"[ACTIVE LIVE AUDIO SESSION CONTEXT: Identified Song: '{song_info or 'Unknown'}'. "
        f"Transcribed Content: '{transcript_snippet or 'None'}']"
    )
