"""Iron Man Anti-Theft & Security Evidence Vault.

Handles:
1. Timestamped disk persistence of silent front/back photos and 5-second rolling audio chunks.
2. Real-time Telegram forwarder (<1s delivery) for photos (sendPhoto), 5s voice (sendVoice), and location (sendLocation).
3. Remote Telegram bot command receiver (/blackbox, /siren, /lock, /photo, /location, /standdown).
4. Local evidence index and query endpoints.
"""

import asyncio
from datetime import datetime, timezone
import json
import logging
import os
import shutil
import time
from typing import Any, Dict, List, Optional
import httpx

logger = logging.getLogger("openjarvis.security_vault")

VAULT_BASE_DIR = os.getenv("OPENJARVIS_VAULT_DIR", "./data/security_vault")
CONFIG_FILE = os.path.join(VAULT_BASE_DIR, "telegram_config.json")
LOCATIONS_FILE = os.path.join(VAULT_BASE_DIR, "locations_history.jsonl")


class SecurityEvidenceVault:
    """Singleton vault for recording security evidence and forwarding to Telegram."""

    _instance: Optional["SecurityEvidenceVault"] = None

    def __init__(self, base_dir: str = VAULT_BASE_DIR):
        self.base_dir = os.path.abspath(base_dir)
        os.makedirs(self.base_dir, exist_ok=True)
        self.config_path = os.path.join(self.base_dir, "telegram_config.json")
        self.locations_path = os.path.join(self.base_dir, "locations_history.jsonl")
        self._pending_commands: List[Dict[str, Any]] = []
        self._last_update_id = 0
        self._polling_task: Optional[asyncio.Task] = None
        self._lock = asyncio.Lock()
        self._load_config()

    @classmethod
    def get_instance(cls) -> "SecurityEvidenceVault":
        if cls._instance is None:
            cls._instance = SecurityEvidenceVault()
        return cls._instance

    def _load_config(self) -> Dict[str, Any]:
        cfg = {
            "bot_token": os.getenv("TELEGRAM_BOT_TOKEN", ""),
            "chat_id": os.getenv("TELEGRAM_CHAT_ID", ""),
            "enabled": True,
        }
        if os.path.exists(self.config_path):
            try:
                with open(self.config_path, "r", encoding="utf-8") as f:
                    file_cfg = json.load(f)
                    cfg.update(file_cfg)
            except Exception as e:
                logger.warning(f"Failed to read {self.config_path}: {e}")
        self.config = cfg
        return cfg

    def save_telegram_config(self, bot_token: str, chat_id: str, enabled: bool = True) -> Dict[str, Any]:
        self.config = {
            "bot_token": bot_token.strip(),
            "chat_id": chat_id.strip(),
            "enabled": enabled,
            "updated_at": datetime.now(timezone.utc).isoformat(),
        }
        try:
            with open(self.config_path, "w", encoding="utf-8") as f:
                json.dump(self.config, f, indent=2)
            logger.info("Saved Telegram security vault configuration.")
        except Exception as e:
            logger.error(f"Failed to save {self.config_path}: {e}")
        return self.get_telegram_status()

    def get_telegram_status(self) -> Dict[str, Any]:
        token = self.config.get("bot_token", "")
        chat_id = self.config.get("chat_id", "")
        masked_token = (token[:6] + "..." + token[-4:]) if len(token) > 10 else ("***" if token else "")
        masked_chat_id = (chat_id[:3] + "..." + chat_id[-2:]) if len(chat_id) > 5 else chat_id
        return {
            "configured": bool(token and chat_id),
            "enabled": self.config.get("enabled", True),
            "masked_token": masked_token,
            "masked_chat_id": masked_chat_id,
            "polling_active": self._polling_task is not None and not self._polling_task.done(),
        }

    def save_evidence(
        self,
        file_bytes: bytes,
        filename: str,
        evidence_type: str = "audio",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        """Save evidence file to disk and queue/trigger Telegram forwarding."""
        now = datetime.now(timezone.utc)
        date_folder = now.strftime("%Y-%m-%d")
        dest_dir = os.path.join(self.base_dir, date_folder)
        os.makedirs(dest_dir, exist_ok=True)

        timestamp_str = now.strftime("%Y%m%d_%H%M%S_%f")[:19]
        safe_name = os.path.basename(filename)
        ext = os.path.splitext(safe_name)[1] or (".jpg" if evidence_type == "photo" else ".wav")
        saved_filename = f"{timestamp_str}_{evidence_type}{ext}"
        saved_path = os.path.join(dest_dir, saved_filename)

        with open(saved_path, "wb") as f:
            f.write(file_bytes)

        meta = {
            "id": f"evid_{int(time.time() * 1000)}",
            "type": evidence_type,
            "original_filename": safe_name,
            "stored_filename": saved_filename,
            "stored_path": saved_path,
            "size_bytes": len(file_bytes),
            "created_at": now.isoformat(),
            "metadata": metadata or {},
        }
        meta_path = os.path.join(dest_dir, f"{timestamp_str}_{evidence_type}.json")
        try:
            with open(meta_path, "w", encoding="utf-8") as f:
                json.dump(meta, f, indent=2)
        except Exception as e:
            logger.warning(f"Failed to write metadata {meta_path}: {e}")

        # Async Telegram Forward
        forward_task = asyncio.create_task(
            self._forward_evidence_async(file_bytes, saved_filename, evidence_type, meta)
        )

        return {
            "status": "stored",
            "id": meta["id"],
            "filename": saved_filename,
            "path": saved_path,
            "size_bytes": len(file_bytes),
            "type": evidence_type,
            "telegram_queued": bool(self.config.get("bot_token") and self.config.get("chat_id")),
        }

    async def _forward_evidence_async(
        self,
        file_bytes: bytes,
        filename: str,
        evidence_type: str,
        meta: Dict[str, Any],
    ) -> bool:
        bot_token = self.config.get("bot_token", "")
        chat_id = self.config.get("chat_id", "")
        if not bot_token or not chat_id or not self.config.get("enabled", True):
            return False

        try:
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            async with httpx.AsyncClient(timeout=15.0) as client:
                if evidence_type == "photo":
                    caption = f"🚨 JARVIS SECURITY ALERT: Photo Evidence\n📅 Time: {now_str}\n📁 {filename}"
                    url = f"https://api.telegram.org/bot{bot_token}/sendPhoto"
                    files = {"photo": (filename, file_bytes, "image/jpeg")}
                    data = {"chat_id": chat_id, "caption": caption}
                    resp = await client.post(url, data=data, files=files)
                    return resp.status_code == 200
                else:
                    caption = f"🎙️ JARVIS SECURITY ALERT: 5s Audio Evidence\n📅 Time: {now_str}\n📁 {filename}"
                    url = f"https://api.telegram.org/bot{bot_token}/sendVoice"
                    files = {"voice": (filename, file_bytes, "audio/wav")}
                    data = {"chat_id": chat_id, "caption": caption}
                    resp = await client.post(url, data=data, files=files)
                    if resp.status_code != 200:
                        # Fallback to sendAudio
                        url = f"https://api.telegram.org/bot{bot_token}/sendAudio"
                        files = {"audio": (filename, file_bytes, "audio/wav")}
                        resp = await client.post(url, data=data, files=files)
                    return resp.status_code == 200
        except Exception as e:
            logger.error(f"Error forwarding evidence to Telegram: {e}")
            return False

    def save_location(self, location_data: Dict[str, Any]) -> Dict[str, Any]:
        """Save GPS/Network location telemetry and forward to Telegram."""
        now = datetime.now(timezone.utc)
        record = {
            "timestamp": now.isoformat(),
            "data": location_data,
        }
        try:
            with open(self.locations_path, "a", encoding="utf-8") as f:
                f.write(json.dumps(record, ensure_ascii=False) + "\n")
        except Exception as e:
            logger.warning(f"Failed to record location to {self.locations_path}: {e}")

        # Async Telegram Forward
        asyncio.create_task(self._forward_location_async(location_data))
        return {
            "status": "recorded",
            "timestamp": record["timestamp"],
            "location": location_data,
            "telegram_queued": bool(self.config.get("bot_token") and self.config.get("chat_id")),
        }

    async def _forward_location_async(self, location_data: Dict[str, Any]) -> bool:
        bot_token = self.config.get("bot_token", "")
        chat_id = self.config.get("chat_id", "")
        if not bot_token or not chat_id or not self.config.get("enabled", True):
            return False

        lat = location_data.get("latitude")
        lng = location_data.get("longitude")
        if lat is None or lng is None:
            return False

        try:
            now_str = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
            accuracy = location_data.get("accuracy", "N/A")
            battery = location_data.get("battery", "N/A")
            maps_url = f"https://www.google.com/maps?q={lat},{lng}"

            msg = (
                f"📍 JARVIS SECURITY ALERT: Device Location\n"
                f"📅 Time: {now_str}\n"
                f"🎯 Coordinates: {lat:.6f}, {lng:.6f}\n"
                f"📡 Accuracy: {accuracy}m | 🔋 Battery: {battery}%\n"
                f"🗺️ Maps: {maps_url}"
            )

            async with httpx.AsyncClient(timeout=10.0) as client:
                # 1. Send native Telegram pin
                loc_url = f"https://api.telegram.org/bot{bot_token}/sendLocation"
                await client.post(loc_url, json={"chat_id": chat_id, "latitude": lat, "longitude": lng})
                # 2. Send detailed text message with Google Maps link
                msg_url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
                resp = await client.post(msg_url, json={"chat_id": chat_id, "text": msg, "disable_web_page_preview": False})
                return resp.status_code == 200
        except Exception as e:
            logger.error(f"Error forwarding location to Telegram: {e}")
            return False

    async def test_telegram_connection(self) -> Dict[str, Any]:
        """Verify bot token and chat_id with an immediate Telegram ping."""
        bot_token = self.config.get("bot_token", "")
        chat_id = self.config.get("chat_id", "")
        if not bot_token or not chat_id:
            return {"status": "error", "message": "Bot token or Chat ID is missing."}

        try:
            url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
            test_msg = (
                "🛡️ <b>J.A.R.V.I.S. Iron Man Security Vault</b>\n\n"
                "✅ <i>Telegram Alert Forwarder Verified!</i>\n"
                "Silent photos, 5s rolling audio, and location breadcrumbs will appear here instantly."
            )
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.post(url, json={
                    "chat_id": chat_id,
                    "text": test_msg,
                    "parse_mode": "HTML",
                })
                if resp.status_code == 200:
                    return {"status": "success", "message": "Telegram ping delivered successfully!"}
                else:
                    return {"status": "error", "message": f"Telegram API returned HTTP {resp.status_code}: {resp.text}"}
        except Exception as e:
            return {"status": "error", "message": f"Network exception: {str(e)}"}

    async def send_telegram_text(self, text: str, parse_mode: Optional[str] = "HTML") -> Dict[str, Any]:
        """Forward a text alert, brain-dump note, or clipboard item to configured Telegram chat."""
        clean_msg = (text or "").strip()
        if not clean_msg:
            return {"status": "error", "forwarded": False, "message": "Empty text"}

        bot_token = self.config.get("bot_token", "")
        chat_id = self.config.get("chat_id", "")
        if not bot_token or not chat_id or not self.config.get("enabled", True):
            return {"status": "skipped", "forwarded": False, "message": "Telegram Vault not configured or disabled."}

        try:
            url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
            payload: Dict[str, Any] = {"chat_id": chat_id, "text": clean_msg}
            if parse_mode:
                payload["parse_mode"] = parse_mode
            async with httpx.AsyncClient(timeout=8.0) as client:
                resp = await client.post(url, json=payload)
                if resp.status_code == 200:
                    return {"status": "sent", "forwarded": True}
                if parse_mode:
                    # Retry without parse_mode in case text contained raw < or & characters
                    resp2 = await client.post(url, json={"chat_id": chat_id, "text": clean_msg})
                    if resp2.status_code == 200:
                        return {"status": "sent", "forwarded": True}
                return {"status": "error", "forwarded": False, "message": f"HTTP {resp.status_code}"}
        except Exception as e:
            logger.debug(f"send_telegram_text exception: {e}")
            return {"status": "error", "forwarded": False, "message": str(e)}

    # -------------------------------------------------------------------------
    # Remote Bot Commands & Polling
    # -------------------------------------------------------------------------
    def add_pending_command(self, command: str, parameters: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        cmd_obj = {
            "id": f"cmd_{int(time.time() * 1000)}",
            "command": command.strip().lower(),
            "parameters": parameters or {},
            "created_at": datetime.now(timezone.utc).isoformat(),
        }
        self._pending_commands.append(cmd_obj)
        logger.info(f"Queued remote security command: {cmd_obj}")
        return cmd_obj

    def pop_pending_commands(self) -> List[Dict[str, Any]]:
        cmds = list(self._pending_commands)
        self._pending_commands.clear()
        return cmds

    def peek_pending_commands(self) -> List[Dict[str, Any]]:
        return list(self._pending_commands)

    async def poll_telegram_bot_updates(self) -> None:
        """Poll incoming commands from Telegram bot chat."""
        bot_token = self.config.get("bot_token", "")
        chat_id = str(self.config.get("chat_id", "")).strip()
        if not bot_token:
            return

        url = f"https://api.telegram.org/bot{bot_token}/getUpdates"
        try:
            async with httpx.AsyncClient(timeout=12.0) as client:
                resp = await client.get(url, params={"offset": self._last_update_id + 1, "timeout": 2})
                if resp.status_code != 200:
                    return
                data = resp.json()
                for update in data.get("result", []):
                    self._last_update_id = max(self._last_update_id, update.get("update_id", 0))
                    msg = update.get("message", {})
                    from_id = str(msg.get("chat", {}).get("id", ""))
                    text = msg.get("text", "").strip()

                    # Only respond to authorized chat_id if set
                    if chat_id and from_id != chat_id:
                        logger.warning(f"Ignoring command from unauthorized chat {from_id}")
                        continue

                    if not text.startswith("/"):
                        continue

                    parts = text.split()
                    cmd = parts[0].lower().replace("@", " ").split()[0]
                    arg = parts[1] if len(parts) > 1 else ""

                    reply_text = ""
                    if cmd in ("/blackbox", "/redalert"):
                        self.add_pending_command("blackbox")
                        reply_text = "🚨 Protocol Blackbox queued! Silent photos, 5s audio stream, and location loop initiating."
                    elif cmd in ("/siren", "/alarm"):
                        self.add_pending_command("siren")
                        reply_text = "📢 Protocol Siren queued! 100% max volume siren, strobe flashlight, and lock engaged."
                    elif cmd in ("/lock", "/lockdown"):
                        self.add_pending_command("lock")
                        reply_text = "🔒 Screen lock command queued."
                    elif cmd in ("/photo", "/snap"):
                        self.add_pending_command("photo")
                        reply_text = "📷 Silent photo capture requested from device."
                    elif cmd in ("/location", "/gps"):
                        self.add_pending_command("location")
                        reply_text = "📍 Location breadcrumb requested from device."
                    elif cmd in ("/standdown", "/disarm"):
                        self.add_pending_command("standdown", {"passphrase": arg})
                        reply_text = "🛡️ Protocol Stand Down requested. Verifying credentials."
                    elif cmd in ("/status", "/start"):
                        status = self.get_telegram_status()
                        reply_text = (
                            "🤖 <b>J.A.R.V.I.S. Iron Man Security Suite Online</b>\n\n"
                            "Commands:\n"
                            "• /blackbox - Silent 5s audio, photo, location loop\n"
                            "• /siren - Piercing siren + strobe flashlight\n"
                            "• /lock - Lock phone screen immediately\n"
                            "• /photo - Snap silent front+back photo\n"
                            "• /location - Get current GPS location\n"
                            "• /standdown &lt;passphrase&gt; - Disarm protocols"
                        )

                    if reply_text:
                        await client.post(f"https://api.telegram.org/bot{bot_token}/sendMessage", json={
                            "chat_id": from_id,
                            "text": reply_text,
                            "parse_mode": "HTML",
                        })
        except Exception as e:
            logger.debug(f"Telegram polling tick note: {e}")

    def start_polling_loop(self) -> None:
        if self._polling_task is not None and not self._polling_task.done():
            return

        async def _loop():
            logger.info("Started Telegram remote security command polling loop.")
            while True:
                bot_token = str(self.config.get("bot_token", "") or "").strip()
                if not bot_token:
                    await asyncio.sleep(10.0)
                    continue
                try:
                    await self.poll_telegram_bot_updates()
                except Exception as e:
                    logger.debug(f"Polling loop exception: {e}")
                await asyncio.sleep(4.0)

        try:
            loop = asyncio.get_running_loop()
        except RuntimeError:
            loop = asyncio.get_event_loop()
        self._polling_task = loop.create_task(_loop())
