"""Persistent Long-Term Memory Engine for Open-Jarvis (SQLite + FTS5 + Persian/English Normalization).

Provides persistent storage for user facts, preferences, reminders, and rolling chat history.
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

logger = logging.getLogger("openjarvis.memory")


def normalize_text(text: str) -> str:
    """Normalize Persian, Arabic, and English text for fuzzy matching and FTS."""
    if not text:
        return ""
    t = str(text)
    # Arabic Yeh / Farsi Yeh normalization
    t = t.replace("\u064A", "\u06CC")  # ي -> ی
    t = t.replace("\u0649", "\u06CC")  # ى -> ی
    t = t.replace("\u0643", "\u06A9")  # ك -> ک
    # ZWNJ (Zero-Width Non-Joiner) and invisible marks
    t = t.replace("\u200c", " ")
    t = t.replace("\u200d", "")
    t = t.replace("\ufeff", "")
    # Alef variations
    t = re.sub(r"[\u0622\u0623\u0625]", "\u0627", t)  # آ, أ, إ -> ا
    # Heh / Teh Marbuta
    t = t.replace("\u0629", "\u0647")  # ة -> ه
    # Persian & Arabic digits to standard ASCII
    ar_digits = "٠١٢٣٤٥٦٧٨٩"
    fa_digits = "۰۱۲۳۴۵۶۷۸۹"
    for i, (ad, fd) in enumerate(zip(ar_digits, fa_digits)):
        t = t.replace(ad, str(i)).replace(fd, str(i))
    # English lowercasing
    t = t.lower()
    # Normalize spaces
    t = re.sub(r"\s+", " ", t).strip()
    return t


def extract_keywords(text: str) -> str:
    """Extract distinctive token keywords for search indexing."""
    norm = normalize_text(text)
    words = re.findall(r"[\w\u0600-\u06FF]{3,}", norm)
    # Filter common stopwords
    stop_words = {
        "the", "and", "that", "this", "with", "from", "for", "have", "are",
        "است", "برای", "این", "که", "بود", "شد", "های", "یک", "رو", "را", "از", "به", "در"
    }
    keywords = [w for w in words if w not in stop_words]
    return " ".join(dict.fromkeys(keywords))


def get_default_db_path() -> Path:
    """Resolve database path: /root/open-jarvis/data/jarvis_memory.db or fallback."""
    candidate_paths = [
        Path("/root/open-jarvis/data/jarvis_memory.db"),
        Path("/root/projects/open-jarvis/data/jarvis_memory.db"),
        Path(__file__).resolve().parents[2] / "data" / "jarvis_memory.db",
        Path("./data/jarvis_memory.db"),
    ]
    for p in candidate_paths:
        try:
            p.parent.mkdir(parents=True, exist_ok=True)
            # Test write access
            test_file = p.parent / ".test_write"
            test_file.touch()
            test_file.unlink()
            return p
        except Exception:
            continue
    # Ultimate fallback to current directory
    return Path("./jarvis_memory.db")


class MemoryStore:
    """Thread-safe SQLite persistent memory manager with FTS5 and chat history."""

    _instance: Optional[MemoryStore] = None
    _lock = threading.Lock()

    def __init__(self, db_path: Optional[Path | str] = None) -> None:
        if db_path is not None:
            self.db_path = Path(db_path)
        else:
            self.db_path = get_default_db_path()
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._local = threading.local()
        self._init_db()

    @classmethod
    def get_instance(cls, db_path: Optional[Path | str] = None) -> MemoryStore:
        with cls._lock:
            if cls._instance is None:
                cls._instance = cls(db_path)
            return cls._instance

    def _get_connection(self) -> sqlite3.Connection:
        if not hasattr(self._local, "conn") or self._local.conn is None:
            conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
            conn.row_factory = sqlite3.Row
            # Register custom Python functions in SQLite
            conn.create_function("NORMALIZE_TEXT", 1, normalize_text)
            self._local.conn = conn
        return self._local.conn

    def _init_db(self) -> None:
        """Create tables, FTS virtual table, triggers, and indices."""
        conn = self._get_connection()
        with conn:
            # 1. Main memories table
            conn.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT DEFAULT 'general',
                    content TEXT NOT NULL,
                    keywords TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
            """)

            # 2. FTS5 Virtual Table
            try:
                conn.execute("""
                    CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(
                        content,
                        keywords,
                        content='memories',
                        content_rowid='id'
                    );
                """)
                # Triggers to keep FTS synchronized
                conn.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_ai AFTER INSERT ON memories BEGIN
                        INSERT INTO memories_fts(rowid, content, keywords) VALUES (new.id, new.content, new.keywords);
                    END;
                """)
                conn.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_ad AFTER DELETE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content, keywords) VALUES('delete', old.id, old.content, old.keywords);
                    END;
                """)
                conn.execute("""
                    CREATE TRIGGER IF NOT EXISTS memories_au AFTER UPDATE ON memories BEGIN
                        INSERT INTO memories_fts(memories_fts, rowid, content, keywords) VALUES('delete', old.id, old.content, old.keywords);
                        INSERT INTO memories_fts(rowid, content, keywords) VALUES (new.id, new.content, new.keywords);
                    END;
                """)
            except sqlite3.OperationalError as e:
                logger.warning(f"FTS5 initialization notice (fallback to LIKE): {e}")

            # 3. Rolling chat history table (persists 30 turns across restarts)
            conn.execute("""
                CREATE TABLE IF NOT EXISTS chat_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    role TEXT NOT NULL,
                    content TEXT NOT NULL,
                    timestamp TEXT NOT NULL
                );
            """)

    def remember(self, content: str, category: str = "general") -> Dict[str, Any]:
        """Save a memory fact/preference with deduplication."""
        clean_content = content.strip()
        if not clean_content:
            return {"error": "Content cannot be empty", "success": False}

        norm_content = normalize_text(clean_content)
        kw = extract_keywords(clean_content)
        now_iso = datetime.now(timezone.utc).isoformat()

        conn = self._get_connection()
        with conn:
            # Check for existing near-identical memory
            cur = conn.cursor()
            cur.execute("SELECT id, content, category FROM memories WHERE NORMALIZE_TEXT(content) = ?", (norm_content,))
            row = cur.fetchone()
            if row:
                cur.execute(
                    "UPDATE memories SET updated_at = ?, keywords = ? WHERE id = ?",
                    (now_iso, kw, row["id"])
                )
                return {
                    "id": row["id"],
                    "category": row["category"],
                    "content": row["content"],
                    "status": "updated",
                    "deduplicated": True,
                    "success": True,
                }

            cur.execute(
                "INSERT INTO memories (category, content, keywords, created_at, updated_at) VALUES (?, ?, ?, ?, ?)",
                (category, clean_content, kw, now_iso, now_iso)
            )
            new_id = cur.lastrowid
            return {
                "id": new_id,
                "category": category,
                "content": clean_content,
                "status": "stored",
                "deduplicated": False,
                "success": True,
            }

    def recall(self, query: str, limit: int = 6) -> List[Dict[str, Any]]:
        """Recall top relevant memories using FTS5 and normalized LIKE fallback."""
        clean_q = query.strip()
        if not clean_q:
            return []

        norm_q = normalize_text(clean_q)
        conn = self._get_connection()
        results: Dict[int, Dict[str, Any]] = {}

        # 1. Try FTS5 search
        safe_fts_query = re.sub(r"[^\w\s\u0600-\u06FF]", " ", norm_q).strip()
        fts_tokens = safe_fts_query.split()
        if fts_tokens:
            fts_expr = " OR ".join(f'"{t}"*' for t in fts_tokens if len(t) >= 2)
            if fts_expr:
                try:
                    cur = conn.cursor()
                    cur.execute("""
                        SELECT m.id, m.category, m.content, m.created_at, m.updated_at
                        FROM memories m
                        JOIN memories_fts f ON m.id = f.rowid
                        WHERE memories_fts MATCH ?
                        ORDER BY rank
                        LIMIT ?
                    """, (fts_expr, limit))
                    for row in cur.fetchall():
                        results[row["id"]] = dict(row)
                except Exception as e:
                    logger.debug(f"FTS5 match skipped ({e}), using LIKE fallback")

        # 2. Normalized LIKE fallback for Persian/English partial matching
        if len(results) < limit:
            remaining = limit - len(results)
            tokens = [t for t in norm_q.split() if len(t) >= 2][:4]
            cur = conn.cursor()
            for token in tokens:
                like_pat = f"%{token}%"
                cur.execute("""
                    SELECT id, category, content, created_at, updated_at
                    FROM memories
                    WHERE NORMALIZE_TEXT(content) LIKE ? OR NORMALIZE_TEXT(keywords) LIKE ?
                    ORDER BY id DESC
                    LIMIT ?
                """, (like_pat, like_pat, remaining))
                for row in cur.fetchall():
                    if row["id"] not in results:
                        results[row["id"]] = dict(row)
                        if len(results) >= limit:
                            break
                if len(results) >= limit:
                    break

        return list(results.values())[:limit]

    def forget(self, query: str) -> int:
        """Delete memories matching query or specific ID string."""
        clean_q = query.strip()
        if not clean_q:
            return 0

        conn = self._get_connection()
        with conn:
            cur = conn.cursor()
            # If query is a numeric ID
            if clean_q.isdigit():
                cur.execute("DELETE FROM memories WHERE id = ?", (int(clean_q),))
                return cur.rowcount

            # Delete matching content
            norm_q = normalize_text(clean_q)
            cur.execute("DELETE FROM memories WHERE NORMALIZE_TEXT(content) LIKE ?", (f"%{norm_q}%",))
            count = cur.rowcount

            # If no direct match, check recalled matches
            if count == 0:
                matches = self.recall(clean_q, limit=5)
                if matches:
                    ids = [m["id"] for m in matches]
                    placeholders = ",".join("?" for _ in ids)
                    cur.execute(f"DELETE FROM memories WHERE id IN ({placeholders})", ids)
                    count = cur.rowcount
            return count

    def delete_memory(self, memory_id: int) -> bool:
        """Delete a single memory by ID."""
        conn = self._get_connection()
        with conn:
            cur = conn.cursor()
            cur.execute("DELETE FROM memories WHERE id = ?", (memory_id,))
            return cur.rowcount > 0

    def list_memories(self, limit: int = 100) -> List[Dict[str, Any]]:
        """List all stored memories ordered by recency."""
        conn = self._get_connection()
        cur = conn.cursor()
        cur.execute("SELECT id, category, content, keywords, created_at, updated_at FROM memories ORDER BY id DESC LIMIT ?", (limit,))
        return [dict(row) for row in cur.fetchall()]

    def clear_all_memories(self) -> int:
        """Clear all stored long-term memories."""
        conn = self._get_connection()
        with conn:
            cur = conn.cursor()
            cur.execute("SELECT COUNT(*) as cnt FROM memories")
            count = cur.fetchone()["cnt"]
            cur.execute("DELETE FROM memories")
            try:
                cur.execute("DELETE FROM memories_fts")
            except Exception:
                pass
            return count

    # -------------------------------------------------------------------------
    # Persistent Rolling Chat History (30 turns)
    # -------------------------------------------------------------------------

    def save_chat_turn(self, role: str, content: str) -> None:
        """Append a chat turn and trim older records past 30 turns."""
        clean_content = content.strip()
        if not clean_content:
            return

        now_iso = datetime.now(timezone.utc).isoformat()
        conn = self._get_connection()
        with conn:
            conn.execute(
                "INSERT INTO chat_history (role, content, timestamp) VALUES (?, ?, ?)",
                (role, clean_content, now_iso)
            )
            # Retain only the latest 30 turns
            conn.execute("""
                DELETE FROM chat_history
                WHERE id NOT IN (
                    SELECT id FROM chat_history ORDER BY id DESC LIMIT 30
                )
            """)

    def get_recent_chat_history(self, limit: int = 30) -> List[Dict[str, str]]:
        """Retrieve recent conversation turns in chronological order."""
        conn = self._get_connection()
        cur = conn.cursor()
        cur.execute(
            "SELECT role, content FROM chat_history ORDER BY id DESC LIMIT ?",
            (limit,)
        )
        rows = cur.fetchall()
        # Return in chronological order
        return [{"role": r["role"], "content": r["content"]} for r in reversed(rows)]


def get_memory_store() -> MemoryStore:
    """Helper to access singleton MemoryStore."""
    return MemoryStore.get_instance()
