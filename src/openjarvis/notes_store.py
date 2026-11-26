"""Lightweight SQLite Quick Notes & Brain Dump Store for Open-Jarvis (Zero LLM Tokens).

Provides persistent capture of spoken ideas, tasks, quotes, and general notes with
deterministic heuristic auto-tagging and optional Telegram Vault mirroring.
"""

from __future__ import annotations

import logging
import os
import re
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger("openjarvis.notes")

VALID_NOTE_CATEGORIES = ("task", "idea", "quote", "general")

TASK_KEYWORDS = (
    "todo", "to do", "task", "remind", "reminder", "buy", "call", "email", "fix", "submit",
    "deadline", "finish", "prepare", "check", "pay", "schedule",
    "کار", "انجام", "بخرم", "زنگ بزنم", "تماس", "ایمیل", "یادم", "یادآوری", "فراموش نکنم",
    "پروژه", "بفرستم", "پرداخت", "قرار", "واجب", "ضروری", "تکمیل",
)

IDEA_KEYWORDS = (
    "idea", "brainstorm", "concept", "what if", "build", "design", "invent", "startup",
    "feature", "prototype", "architecture",
    "ایده", "طرح", "پیشنهاد", "فکر", "بسازم", "طراحی", "نوآوری", "اپلیکیشن", "استارتاپ",
)

QUOTE_KEYWORDS = (
    "quote", "saying", "proverb", "motto", "wisdom",
    "نقل قول", "جمله", "گفته", "شعر", "حکمت", "ضرب المثل", "ضرب‌المثل", "سخن",
)


def auto_tag_note_category(text: str, explicit_category: Optional[str] = None) -> str:
    """Heuristically classify a note into task | idea | quote | general with 0 LLM tokens."""
    if explicit_category:
        cat = explicit_category.strip().lower()
        if cat in VALID_NOTE_CATEGORIES:
            return cat

    clean = (text or "").strip().lower()
    if not clean:
        return "general"

    if '"' in clean or "«" in clean or "»" in clean or any(k in clean for k in QUOTE_KEYWORDS):
        return "quote"
    if any(k in clean for k in IDEA_KEYWORDS):
        return "idea"
    if any(k in clean for k in TASK_KEYWORDS):
        return "task"
    return "general"


def get_default_notes_db_path() -> Path:
    """Resolve notes SQLite database path."""
    env_path = os.getenv("OPENJARVIS_NOTES_DB", "").strip()
    if env_path:
        p = Path(env_path)
        p.parent.mkdir(parents=True, exist_ok=True)
        return p

    candidate_paths = [
        Path("/root/open-jarvis/data/jarvis_notes.db"),
        Path("/root/projects/open-jarvis/data/jarvis_notes.db"),
        Path(__file__).resolve().parents[2] / "data" / "jarvis_notes.db",
        Path("./data/jarvis_notes.db"),
    ]
    for p in candidate_paths:
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            test_file = p.parent / ".test_notes_write"
            test_file.touch()
            test_file.unlink()
            return p
        except Exception:
            continue
    return Path("./jarvis_notes.db")


class NotesStore:
    """Thread-safe SQLite store for Quick Notes & Brain Dump captures."""

    _instance: Optional["NotesStore"] = None
    _lock = threading.Lock()

    def __init__(self, db_path: Optional[Path | str] = None) -> None:
        self.db_path = Path(db_path) if db_path is not None else get_default_notes_db_path()
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._init_db()

    @classmethod
    def get_instance(cls, db_path: Optional[Path | str] = None) -> "NotesStore":
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls(db_path)
            return cls._instance

    def _get_connection(self) -> sqlite3.Connection:
        if not hasattr(self._local, "conn") or self._local.conn is None:
            conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
            conn.row_factory = sqlite3.Row
            self._local.conn = conn
        return self._local.conn

    def _init_db(self) -> None:
        conn = self._get_connection()
        with conn:
            conn.execute("""
                CREATE TABLE IF NOT EXISTS notes (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    category TEXT NOT NULL DEFAULT 'general',
                    text TEXT NOT NULL
                );
            """)
            conn.execute("CREATE INDEX IF NOT EXISTS idx_notes_created_at ON notes(created_at);")
            conn.execute("CREATE INDEX IF NOT EXISTS idx_notes_category ON notes(category);")

    def save_note(self, text: str, category: Optional[str] = None) -> Dict[str, Any]:
        """Save a note cleanly into SQLite with zero-token heuristic category tagging."""
        clean_text = (text or "").strip()
        if not clean_text:
            return {"error": "Note text cannot be empty", "success": False}

        resolved_category = auto_tag_note_category(clean_text, category)
        now_iso = datetime.now(timezone.utc).isoformat()

        conn = self._get_connection()
        with conn:
            cur = conn.cursor()
            cur.execute(
                "INSERT INTO notes (created_at, category, text) VALUES (?, ?, ?)",
                (now_iso, resolved_category, clean_text),
            )
            note_id = cur.lastrowid

        return {
            "id": note_id,
            "created_at": now_iso,
            "category": resolved_category,
            "text": clean_text,
            "status": "saved",
            "success": True,
        }

    def list_notes(
        self,
        today: bool = False,
        limit: int = 20,
        category: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        """List recent or today's notes sorted newest-first."""
        safe_limit = min(max(int(limit), 1), 100)
        conn = self._get_connection()
        cur = conn.cursor()

        clauses: List[str] = []
        params: List[Any] = []

        if today:
            today_prefix = datetime.now(timezone.utc).strftime("%Y-%m-%d")
            local_prefix = datetime.now().strftime("%Y-%m-%d")
            if today_prefix == local_prefix:
                clauses.append("created_at LIKE ?")
                params.append(f"{today_prefix}%")
            else:
                clauses.append("(created_at LIKE ? OR created_at LIKE ?)")
                params.extend([f"{today_prefix}%", f"{local_prefix}%"])

        if category and category.strip().lower() in VALID_NOTE_CATEGORIES:
            clauses.append("category = ?")
            params.append(category.strip().lower())

        where_sql = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        sql = f"SELECT id, created_at, category, text FROM notes {where_sql} ORDER BY id DESC LIMIT ?"
        params.append(safe_limit)

        cur.execute(sql, tuple(params))
        return [dict(row) for row in cur.fetchall()]

    def delete_note(self, note_id: int) -> bool:
        """Delete a note by ID."""
        conn = self._get_connection()
        with conn:
            cur = conn.cursor()
            cur.execute("DELETE FROM notes WHERE id = ?", (note_id,))
            return cur.rowcount > 0
