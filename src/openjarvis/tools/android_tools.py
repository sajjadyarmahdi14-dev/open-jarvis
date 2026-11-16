"""Android System Action Tools using Termux:API and Android Intents.

Provides deep native integration for Android devices:
- Notification management (termux-notification)
- System clipboard access (termux-clipboard-get / set)
- Toast popups (termux-toast)
- Haptic feedback (termux-vibrate)
- Battery & power status (termux-battery-status)
- Volume mixer control (termux-volume)
- Flashlight / Torch control (termux-torch)
- Media player control (termux-media-player)
- Android Intent execution (am start) to open URLs, maps, dialer, or apps

Every tool is guarded with strict execution timeouts and graceful fallbacks
to prevent hanging if Termux:API app permissions are not yet granted.
"""

from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
from typing import Any, Dict, List, Optional, Tuple

from openjarvis.core.registry import ToolRegistry
from openjarvis.core.types import ToolResult
from openjarvis.tools._stubs import BaseTool, ToolSpec

logger = logging.getLogger(__name__)

DEFAULT_TIMEOUT = float(os.environ.get("TERMUX_API_TIMEOUT", 10.0))


def _run_termux_cmd(
    cmd: List[str],
    timeout: float = DEFAULT_TIMEOUT,
    input_text: Optional[str] = None,
    extra_env: Optional[Dict[str, str]] = None,
) -> Tuple[bool, str]:
    """Execute a Termux:API or Android shell command with strict timeout guards."""
    binary = cmd[0]
    env = dict(os.environ)
    system_paths = "/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin"
    current_path = env.get("PATH", "")
    env["PATH"] = f"{system_paths}:{current_path}"
    if extra_env:
        env.update(extra_env)

    if not shutil.which(binary, path=env["PATH"]):
        return (
            False,
            f"Binary '{binary}' was not found on PATH. "
            "Please ensure Termux:API is installed (`pkg install termux-api`) "
            "and the Termux:API companion app is installed on your Android device.",
        )

    try:
        res = subprocess.run(
            cmd,
            input=input_text,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            env=env,
        )
        if res.returncode != 0:
            err = res.stderr.strip() or f"Command exited with code {res.returncode}"
            return False, err
        return True, res.stdout.strip()
    except subprocess.TimeoutExpired:
        return (
            False,
            f"Command '{binary}' timed out after {timeout}s. "
            "Ensure the Termux:API Android companion app has background execution "
            "and required Android system permissions granted.",
        )
    except Exception as exc:
        return False, f"Execution failed for '{binary}': {exc}"


# ---------------------------------------------------------------------------
# 1. Android Notification
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_notification")
class AndroidNotificationTool(BaseTool):
    """Post an Android status bar notification."""

    tool_id = "android_notification"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_notification",
            description="Display a system notification in the Android status bar.",
            parameters={
                "type": "object",
                "properties": {
                    "title": {
                        "type": "string",
                        "description": "Notification title",
                    },
                    "content": {
                        "type": "string",
                        "description": "Notification body content",
                    },
                    "priority": {
                        "type": "string",
                        "enum": ["high", "low", "default", "max", "min"],
                        "description": "Notification priority level (default: high)",
                    },
                    "id": {
                        "type": "string",
                        "description": "Unique notification ID for updating/clearing",
                    },
                    "action": {
                        "type": "string",
                        "description": "Optional URL or command to launch on notification click",
                    },
                },
                "required": ["title", "content"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        title = params.get("title", "Jarvis")
        content = params.get("content", "")
        priority = params.get("priority", "high")
        notif_id = params.get("id", "openjarvis")
        action = params.get("action")

        cmd = [
            "termux-notification",
            "--title",
            title,
            "--content",
            content,
            "--priority",
            priority,
            "--id",
            notif_id,
        ]
        if action:
            cmd.extend(["--action", action])

        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content=f"Notification posted: '{title} - {content}'",
            success=True,
        )


# ---------------------------------------------------------------------------
# 2. Android Clipboard Get & Set
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_clipboard_get")
class AndroidClipboardGetTool(BaseTool):
    """Read text from the Android system clipboard."""

    tool_id = "android_clipboard_get"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_clipboard_get",
            description="Get the current text content from the Android system clipboard.",
            parameters={"type": "object", "properties": {}},
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        ok, msg = _run_termux_cmd(["termux-clipboard-get"])
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content=msg if msg else "(Clipboard is empty)",
            success=True,
        )


@ToolRegistry.register("android_clipboard_set")
class AndroidClipboardSetTool(BaseTool):
    """Copy text to the Android system clipboard."""

    tool_id = "android_clipboard_set"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_clipboard_set",
            description="Set or copy text to the Android system clipboard.",
            parameters={
                "type": "object",
                "properties": {
                    "text": {
                        "type": "string",
                        "description": "Text string to copy to the clipboard",
                    }
                },
                "required": ["text"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        text = params.get("text", "")
        ok, msg = _run_termux_cmd(["termux-clipboard-set", text])
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content="Text successfully copied to Android clipboard.",
            success=True,
        )


# ---------------------------------------------------------------------------
# 3. Android Toast Popup
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_toast")
class AndroidToastTool(BaseTool):
    """Display a quick Android popup toast message."""

    tool_id = "android_toast"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_toast",
            description="Display a brief transient toast popup message on the Android screen.",
            parameters={
                "type": "object",
                "properties": {
                    "text": {
                        "type": "string",
                        "description": "Message to display in toast",
                    },
                    "short": {
                        "type": "boolean",
                        "description": "Whether to use short duration (default: true)",
                    },
                },
                "required": ["text"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        text = params.get("text", "")
        short = params.get("short", True)
        cmd = ["termux-toast"]
        if short:
            cmd.append("-s")
        cmd.append(text)
        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content=f"Toast displayed: '{text}'",
            success=True,
        )


# ---------------------------------------------------------------------------
# 4. Android Vibrate
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_vibrate")
class AndroidVibrateTool(BaseTool):
    """Trigger device haptic vibration."""

    tool_id = "android_vibrate"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_vibrate",
            description="Vibrate the Android device for a specified duration in milliseconds.",
            parameters={
                "type": "object",
                "properties": {
                    "duration_ms": {
                        "type": "integer",
                        "description": "Vibration duration in milliseconds (default: 400)",
                    },
                    "force": {
                        "type": "boolean",
                        "description": "Force vibration even in silent mode",
                    },
                },
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        duration_ms = params.get("duration_ms", 400)
        force = params.get("force", False)
        cmd = ["termux-vibrate", "-d", str(int(duration_ms))]
        if force:
            cmd.append("-f")
        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content=f"Vibrated device for {duration_ms}ms.",
            success=True,
        )


# ---------------------------------------------------------------------------
# 5. Android Battery Status
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_battery")
class AndroidBatteryTool(BaseTool):
    """Query battery percentage, health, temperature, and charging status."""

    tool_id = "android_battery"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_battery",
            description="Get device battery level, health, temperature, and power connection status.",
            parameters={"type": "object", "properties": {}},
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        ok, msg = _run_termux_cmd(["termux-battery-status"])
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        try:
            data = json.loads(msg)
            pct = data.get("percentage")
            status = data.get("status")
            plugged = data.get("plugged")
            health = data.get("health")
            temp = data.get("temperature")
            summary = (
                f"Battery: {pct}% ({status}, {plugged})\n"
                f"Health: {health}, Temperature: {temp}°C"
            )
            return ToolResult(tool_name=self.spec.name, content=summary, success=True)
        except Exception:
            return ToolResult(tool_name=self.spec.name, content=msg, success=True)


# ---------------------------------------------------------------------------
# 6. Android Volume Control
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_volume")
class AndroidVolumeTool(BaseTool):
    """Get or adjust audio volume for different streams."""

    tool_id = "android_volume"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_volume",
            description="Inspect or set volume levels for audio streams (music, call, notification, ring, alarm, system).",
            parameters={
                "type": "object",
                "properties": {
                    "stream": {
                        "type": "string",
                        "enum": ["music", "call", "system", "ring", "alarm", "notification"],
                        "description": "Audio stream to adjust or inspect",
                    },
                    "volume": {
                        "type": "integer",
                        "description": "Target volume level (omit to inspect current levels)",
                    },
                },
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        stream = params.get("stream")
        volume = params.get("volume")

        if stream and volume is not None:
            cmd = ["termux-volume", stream, str(int(volume))]
        else:
            cmd = ["termux-volume"]

        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(tool_name=self.spec.name, content=msg, success=True)


# ---------------------------------------------------------------------------
# 7. Android Flashlight / Torch
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_torch")
class AndroidTorchTool(BaseTool):
    """Toggle the device camera flashlight / torch on or off."""

    tool_id = "android_torch"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_torch",
            description="Turn the device camera flashlight on or off.",
            parameters={
                "type": "object",
                "properties": {
                    "state": {
                        "type": "string",
                        "enum": ["on", "off"],
                        "description": "Torch state ('on' or 'off')",
                    }
                },
                "required": ["state"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        state = params.get("state", "off").lower()
        if state not in ("on", "off"):
            return ToolResult(tool_name=self.spec.name, content="state must be 'on' or 'off'", success=False)

        ok, msg = _run_termux_cmd(["termux-torch", state])
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(tool_name=self.spec.name, content=f"Flashlight turned {state}.", success=True)


# ---------------------------------------------------------------------------
# 8. Android Media Player
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_media_player")
class AndroidMediaPlayerTool(BaseTool):
    """Control audio playback on Android."""

    tool_id = "android_media_player"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_media_player",
            description="Play, pause, resume, stop, or get info on audio playback.",
            parameters={
                "type": "object",
                "properties": {
                    "command": {
                        "type": "string",
                        "enum": ["play", "pause", "resume", "stop", "info"],
                        "description": "Media player action",
                    },
                    "file": {
                        "type": "string",
                        "description": "File path or URL to play (required for 'play')",
                    },
                },
                "required": ["command"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        command = params.get("command", "info").lower()
        file_path = params.get("file")

        cmd = ["termux-media-player", command]
        if command == "play":
            if not file_path:
                return ToolResult(tool_name=self.spec.name, content="File path is required for 'play'", success=False)
            cmd.append(file_path)

        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(tool_name=self.spec.name, content=msg or f"Media player {command} executed.", success=True)


# ---------------------------------------------------------------------------
# 9. Android Intent Launcher (`am start`)
# ---------------------------------------------------------------------------


@ToolRegistry.register("android_intent")
class AndroidIntentTool(BaseTool):
    """Send an Android Intent to open web URLs, launch apps, or open map locations."""

    tool_id = "android_intent"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_intent",
            description=(
                "Launch an Android Intent via `am start`. Use this to open URLs in the mobile browser, "
                "trigger phone calls (tel:), launch geo maps (geo:), or launch external applications."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "uri": {
                        "type": "string",
                        "description": "Data URI to open (e.g. 'https://github.com', 'geo:37.7749,-122.4194', 'tel:+1234567890')",
                    },
                    "action": {
                        "type": "string",
                        "description": "Intent action (default: 'android.intent.action.VIEW')",
                    },
                    "package": {
                        "type": "string",
                        "description": "Optional package name to target a specific application",
                    },
                },
                "required": ["uri"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        uri = params.get("uri", "")
        action = params.get("action", "android.intent.action.VIEW")
        pkg = params.get("package")

        if not uri:
            return ToolResult(tool_name=self.spec.name, content="URI is required", success=False)

        cmd = ["am", "start", "--user", "0", "-a", action, "-d", uri]
        if pkg:
            cmd.extend(["-p", pkg])

        ok, msg = _run_termux_cmd(cmd)
        if not ok:
            return ToolResult(tool_name=self.spec.name, content=msg, success=False)
        return ToolResult(
            tool_name=self.spec.name,
            content=f"Successfully dispatched Android Intent for URI: {uri}",
            success=True,
        )


COMMON_ANDROID_APPS: Dict[str, str] = {
    # Popular Messenger & Social
    "telegram": "org.telegram.messenger",
    "whatsapp": "com.whatsapp",
    "instagram": "com.instagram.android",
    "discord": "com.discord",
    "twitter": "com.twitter.android",
    "x": "com.twitter.android",
    "tiktok": "com.zhiliaoapp.musically",
    "facebook": "com.facebook.katana",
    "messenger": "com.facebook.orca",
    "reddit": "com.reddit.frontpage",
    # Media & Browsing
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "google chrome": "com.android.chrome",
    "browser": "com.android.chrome",
    "firefox": "org.mozilla.firefox",
    "spotify": "com.spotify.music",
    "netflix": "com.netflix.mediaclient",
    # Popular Mobile Games
    "minecraft": "com.mojang.minecraftpe",
    "roblox": "com.roblox.client",
    "pubg": "com.tencent.ig",
    "bgmi": "com.pubg.imobile",
    "freefire": "com.dts.freefireth",
    "free fire": "com.dts.freefireth",
    "subway surfers": "com.kiloo.subwaysurf",
    "candy crush": "com.king.candycrushsaga",
    "clash of clans": "com.supercell.clashofclans",
    "brawl stars": "com.supercell.brawlstars",
    "genshin": "com.miHoYo.GenshinImpact",
    "genshin impact": "com.miHoYo.GenshinImpact",
    "asphalt": "com.gameloft.android.ANMP.GloftA9HM",
    "cod": "com.activision.callofduty.shooter",
    "call of duty": "com.activision.callofduty.shooter",
    # System & Tools
    "settings": "com.android.settings",
    "camera": "com.android.camera",
    "termux": "com.termux",
    "gmail": "com.google.android.gm",
    "email": "com.google.android.gm",
    "maps": "com.google.android.apps.maps",
    "google maps": "com.google.android.apps.maps",
    "calculator": "com.google.android.calculator",
    "clock": "com.google.android.deskclock",
    "alarm": "com.google.android.deskclock",
    "gallery": "com.google.android.apps.photos",
    "photos": "com.google.android.apps.photos",
    "files": "com.google.android.documentsui",
    "playstore": "com.android.vending",
    "play store": "com.android.vending",
    "contacts": "com.google.android.contacts",
    "messages": "com.google.android.apps.messaging",
    "phone": "com.google.android.dialer",
    "dialer": "com.google.android.dialer",
    "uber": "com.ubercab",
}


def _search_contacts_data(query: str, timeout: float = DEFAULT_TIMEOUT) -> List[Dict[str, str]]:
    """Query Termux contact list and return matching contact dictionaries."""
    ok, out = _run_termux_cmd(["termux-contact-list"], timeout=timeout)
    if not ok or not out:
        return []
    try:
        contacts = json.loads(out)
        if not isinstance(contacts, list):
            return []
        q = query.strip().lower()
        matches = []
        for c in contacts:
            name = str(c.get("name", "")).strip()
            num = str(c.get("number", "")).strip()
            if q in name.lower() or q in num.replace(" ", "").replace("-", ""):
                matches.append({"name": name, "number": num})
        return matches
    except Exception:
        return []


@ToolRegistry.register("android_launch_app")
class AndroidLaunchAppTool(BaseTool):
    """Launch an Android application by common name or package identifier."""

    tool_id = "android_launch_app"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_launch_app",
            description=(
                "Launch an Android application or game by common name (e.g. 'Telegram', 'WhatsApp', "
                "'Minecraft', 'Roblox', 'YouTube', 'Chrome', 'Settings', 'Camera', 'Termux', "
                "'Spotify', 'Instagram', 'Gmail', 'Maps', 'Calculator') or package name."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "app": {
                        "type": "string",
                        "description": "Common name of the app or game (e.g. 'Telegram', 'Minecraft', 'YouTube', 'Settings', 'Camera') or package name",
                    },
                    "package": {
                        "type": "string",
                        "description": "Optional explicit package name to launch",
                    },
                },
                "required": ["app"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        app_name = str(
            params.get("app")
            or params.get("app_query")
            or params.get("app_name")
            or params.get("name")
            or ""
        ).strip()
        explicit_pkg = params.get("package")

        if not app_name and not explicit_pkg:
            return ToolResult(tool_name=self.spec.name, content="App name or package is required", success=False)

        pkg = explicit_pkg
        if not pkg:
            app_lower = app_name.lower()
            if "." in app_name and " " not in app_name:
                pkg = app_name
            elif app_lower in COMMON_ANDROID_APPS:
                pkg = COMMON_ANDROID_APPS[app_lower]
            else:
                for key, val in COMMON_ANDROID_APPS.items():
                    if key in app_lower or app_lower in key:
                        pkg = val
                        break

            if not pkg:
                ok, output = _run_termux_cmd(["pm", "list", "packages", app_lower])
                if ok and output:
                    for line in output.splitlines():
                        if "package:" in line:
                            candidate = line.replace("package:", "").strip()
                            if candidate:
                                pkg = candidate
                                break

        if not pkg:
            pkg = app_name

        # Known component shortcuts
        COMPONENTS = {
            "termux": "com.termux/.app.TermuxActivity",
            "settings": "com.android.settings/.Settings",
            "camera": "com.android.camera",
        }

        # Specialized action shortcuts
        if app_name.lower() in ("camera", "take photo", "photo"):
            ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.media.action.IMAGE_CAPTURE"])
            if ok:
                return ToolResult(tool_name=self.spec.name, content="Launched Camera app via Android Intent", success=True)

        if app_name.lower() in ("settings", "system settings"):
            ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.settings.SETTINGS"])
            if ok:
                return ToolResult(tool_name=self.spec.name, content="Launched Android Settings", success=True)

        if app_name.lower() in COMPONENTS:
            comp = COMPONENTS[app_name.lower()]
            if "/" in comp:
                ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-n", comp])
                if ok and "Error" not in msg:
                    return ToolResult(
                        tool_name=self.spec.name,
                        content=f"Successfully launched '{app_name}' ({comp})",
                        success=True,
                    )

        # Attempt 1: am start launcher intent with user 0
        am_cmd = ["am", "start", "--user", "0", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-p", pkg]
        ok, msg = _run_termux_cmd(am_cmd)
        if ok and "Error:" not in msg:
            return ToolResult(
                tool_name=self.spec.name,
                content=f"Successfully launched '{app_name}' (package: {pkg})",
                success=True,
            )

        # Attempt 2: am start package target
        am_basic = ["am", "start", "--user", "0", "-p", pkg]
        ok, msg = _run_termux_cmd(am_basic)
        if ok and "Error:" not in msg:
            return ToolResult(
                tool_name=self.spec.name,
                content=f"Successfully launched '{app_name}' ({pkg})",
                success=True,
            )

        # Attempt 3: monkey via sh
        monkey_cmd = ["sh", "-c", f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1"]
        ok, msg = _run_termux_cmd(monkey_cmd)
        if ok and "No activities found" not in msg and "Error" not in msg:
            return ToolResult(
                tool_name=self.spec.name,
                content=f"Successfully launched '{app_name}' via monkey",
                success=True,
            )

        return ToolResult(
            tool_name=self.spec.name,
            content=f"Failed to launch '{app_name}' (package: {pkg}): {msg}",
            success=False,
        )


@ToolRegistry.register("android_contacts_search")
class AndroidContactsSearchTool(BaseTool):
    """Search device contacts by name or phone number."""

    tool_id = "android_contacts_search"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_contacts_search",
            description="Search Android device contacts by name or phone number.",
            parameters={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Name or phone number query keyword",
                    },
                },
                "required": ["query"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        query = str(params.get("query", "")).strip()
        if not query:
            return ToolResult(tool_name=self.spec.name, content="Query keyword is required", success=False)

        matches = _search_contacts_data(query)
        if not matches:
            return ToolResult(tool_name=self.spec.name, content=f"No contacts matching '{query}' found in address book.", success=True)

        lines = [f"Found {len(matches)} contact(s) for '{query}':"]
        for c in matches[:10]:
            lines.append(f"• {c['name']}: {c['number']}")
        return ToolResult(tool_name=self.spec.name, content="\n".join(lines), success=True)


@ToolRegistry.register("android_sms_send")
class AndroidSmsSendTool(BaseTool):
    """Send an SMS text message to a phone number or contact name."""

    tool_id = "android_sms_send"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_sms_send",
            description="Send an SMS message to a phone number or contact name.",
            parameters={
                "type": "object",
                "properties": {
                    "recipient": {
                        "type": "string",
                        "description": "Phone number or contact name to send to",
                    },
                    "message": {
                        "type": "string",
                        "description": "Text message content",
                    },
                },
                "required": ["recipient", "message"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        recipient = str(params.get("recipient", "")).strip()
        message = str(params.get("message", "")).strip()

        if not recipient or not message:
            return ToolResult(tool_name=self.spec.name, content="Recipient and message are required", success=False)

        phone_num = recipient
        if any(c.isalpha() for c in recipient):
            matches = _search_contacts_data(recipient)
            if matches:
                phone_num = matches[0]["number"]
            else:
                return ToolResult(
                    tool_name=self.spec.name,
                    content=f"Could not find contact '{recipient}'. Please specify a valid phone number.",
                    success=False,
                )

        clean_num = phone_num.replace(" ", "").replace("-", "")
        cmd = ["termux-sms-send", "-n", clean_num, message]
        ok, msg = _run_termux_cmd(cmd)
        if ok:
            return ToolResult(
                tool_name=self.spec.name,
                content=f"SMS sent successfully to {recipient} ({clean_num}): '{message}'",
                success=True,
            )
        return ToolResult(tool_name=self.spec.name, content=f"Failed to send SMS to {recipient}: {msg}", success=False)


@ToolRegistry.register("android_sms_inbox")
class AndroidSmsInboxTool(BaseTool):
    """Read recent incoming SMS messages."""

    tool_id = "android_sms_inbox"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_sms_inbox",
            description="Read the latest incoming SMS text messages from the Android inbox.",
            parameters={
                "type": "object",
                "properties": {
                    "limit": {
                        "type": "integer",
                        "description": "Number of recent SMS messages to retrieve (default: 5)",
                    },
                },
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        limit = int(params.get("limit", 5))
        cmd = ["termux-sms-list", "-l", str(limit)]
        ok, out = _run_termux_cmd(cmd)
        if not ok or not out:
            return ToolResult(tool_name=self.spec.name, content=f"Could not retrieve SMS messages: {out}", success=False)

        try:
            items = json.loads(out)
            if not isinstance(items, list) or not items:
                return ToolResult(tool_name=self.spec.name, content="No SMS messages found in inbox.", success=True)

            lines = [f"Latest {len(items)} SMS messages:"]
            for item in items:
                sender = item.get("sender") or item.get("number", "Unknown")
                body = item.get("body", "")
                received = item.get("received", "")
                lines.append(f"• From: {sender} ({received})\n  {body}\n")
            return ToolResult(tool_name=self.spec.name, content="\n".join(lines), success=True)
        except Exception as exc:
            return ToolResult(tool_name=self.spec.name, content=f"Error parsing SMS list: {exc}", success=False)


@ToolRegistry.register("android_phone_call")
class AndroidPhoneCallTool(BaseTool):
    """Initiate a phone call to a phone number or contact name."""

    tool_id = "android_phone_call"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_phone_call",
            description="Initiate a phone call to a phone number or contact name.",
            parameters={
                "type": "object",
                "properties": {
                    "recipient": {
                        "type": "string",
                        "description": "Phone number or contact name to call",
                    },
                },
                "required": ["recipient"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        recipient = str(params.get("recipient", "")).strip()
        if not recipient:
            return ToolResult(tool_name=self.spec.name, content="Recipient is required", success=False)

        phone_num = recipient
        if any(c.isalpha() for c in recipient):
            matches = _search_contacts_data(recipient)
            if matches:
                phone_num = matches[0]["number"]
            else:
                return ToolResult(tool_name=self.spec.name, content=f"Contact '{recipient}' not found in address book.", success=False)

        clean_num = phone_num.replace(" ", "").replace("-", "")
        # Attempt 1: termux-telephony-call
        ok, msg = _run_termux_cmd(["termux-telephony-call", clean_num])
        if ok:
            return ToolResult(tool_name=self.spec.name, content=f"Initiated phone call to {recipient} ({clean_num})", success=True)

        # Attempt 2: Intent CALL
        ok2, msg2 = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.CALL", "-d", f"tel:{clean_num}"])
        if ok2 and "Error" not in msg2:
            return ToolResult(tool_name=self.spec.name, content=f"Calling {recipient} ({clean_num}) via Intent", success=True)

        # Attempt 3: Intent DIAL
        ok3, msg3 = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.DIAL", "-d", f"tel:{clean_num}"])
        if ok3 and "Error" not in msg3:
            return ToolResult(tool_name=self.spec.name, content=f"Opened dialer for {recipient} ({clean_num})", success=True)

        return ToolResult(tool_name=self.spec.name, content=f"Failed to initiate call to {recipient}: {msg}", success=False)


@ToolRegistry.register("android_telegram_action")
class AndroidTelegramActionTool(BaseTool):
    """Open a Telegram user, channel, or send a message via Telegram."""

    tool_id = "android_telegram_action"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_telegram_action",
            description="Open a Telegram username, channel, or chat, or send messages via Telegram.",
            parameters={
                "type": "object",
                "properties": {
                    "target": {
                        "type": "string",
                        "description": "Telegram username (e.g. '@durov' or 'durov') or chat ID",
                    },
                    "message": {
                        "type": "string",
                        "description": "Optional message to pre-fill or send",
                    },
                },
                "required": ["target"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        from urllib.parse import quote
        target = str(params.get("target", "")).strip()
        message = str(params.get("message", "")).strip()

        if not target:
            return ToolResult(tool_name=self.spec.name, content="Target is required", success=False)

        clean_target = target.lstrip("@").replace("https://t.me/", "").replace("http://t.me/", "")
        bot_token = os.environ.get("TELEGRAM_BOT_TOKEN")

        if bot_token and message and (clean_target.isdigit() or clean_target.startswith("-")):
            try:
                import httpx
                url = f"https://api.telegram.org/bot{bot_token}/sendMessage"
                with httpx.Client(timeout=10.0) as client:
                    resp = client.post(url, json={"chat_id": clean_target, "text": message})
                    if resp.status_code == 200:
                        return ToolResult(tool_name=self.spec.name, content=f"Sent message to Telegram chat {clean_target} via Telegram API", success=True)
            except Exception as exc:
                logger.debug("Telegram Bot API send failed: %s", exc)

        uri = f"https://t.me/{clean_target}?text={quote(message)}" if message else f"tg://resolve?domain={clean_target}"
        ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", uri])
        if ok:
            return ToolResult(tool_name=self.spec.name, content=f"Opened Telegram for '@{clean_target}'", success=True)

        ok2, msg2 = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", f"https://t.me/{clean_target}"])
        if ok2:
            return ToolResult(tool_name=self.spec.name, content=f"Opened Telegram link for '@{clean_target}'", success=True)

        return ToolResult(tool_name=self.spec.name, content=f"Failed to open Telegram: {msg}", success=False)


@ToolRegistry.register("android_send_email")
class AndroidSendEmailTool(BaseTool):
    """Send an email silently via SMTP or open the native Android email client."""

    tool_id = "android_send_email"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_send_email",
            description="Send an email or compose an email in the Android email client (Gmail).",
            parameters={
                "type": "object",
                "properties": {
                    "to": {
                        "type": "string",
                        "description": "Recipient email address",
                    },
                    "subject": {
                        "type": "string",
                        "description": "Email subject",
                    },
                    "body": {
                        "type": "string",
                        "description": "Email body content",
                    },
                    "background": {
                        "type": "boolean",
                        "description": "If true and SMTP configured in .env, send silently in background",
                    },
                },
                "required": ["to", "subject", "body"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        from urllib.parse import quote
        to = str(params.get("to", "")).strip()
        subject = str(params.get("subject", "")).strip()
        body = str(params.get("body", "")).strip()
        background = bool(params.get("background", False))

        if not to:
            return ToolResult(tool_name=self.spec.name, content="Recipient email is required", success=False)

        if background and os.environ.get("SMTP_USER") and os.environ.get("SMTP_PASS"):
            try:
                import smtplib
                from email.message import EmailMessage
                msg = EmailMessage()
                msg["Subject"] = subject
                msg["From"] = os.environ.get("SMTP_USER")
                msg["To"] = to
                msg.set_content(body)
                host = os.environ.get("SMTP_HOST", "smtp.gmail.com")
                port = int(os.environ.get("SMTP_PORT", 465))
                with smtplib.SMTP_SSL(host, port, timeout=10.0) as smtp:
                    smtp.login(os.environ.get("SMTP_USER"), os.environ.get("SMTP_PASS"))
                    smtp.send_message(msg)
                return ToolResult(tool_name=self.spec.name, content=f"Email sent silently in background to {to}", success=True)
            except Exception as exc:
                logger.debug("Background SMTP failed: %s", exc)

        mailto_uri = f"mailto:{quote(to)}?subject={quote(subject)}&body={quote(body)}"
        ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.SENDTO", "-d", mailto_uri])
        if ok and "Error:" not in msg:
            return ToolResult(tool_name=self.spec.name, content=f"Opened Android email client for '{to}' with subject '{subject}'", success=True)
        return ToolResult(tool_name=self.spec.name, content=f"Failed to open email client: {msg}", success=False)


@ToolRegistry.register("android_camera_capture")
class AndroidCameraCaptureTool(BaseTool):
    """Capture a photo with the Android camera and optionally analyze it with vision AI."""

    tool_id = "android_camera_capture"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_camera_capture",
            description="Capture a photo with the Android camera and analyze the scene.",
            parameters={
                "type": "object",
                "properties": {
                    "camera_id": {
                        "type": "integer",
                        "description": "Camera ID (0 for back camera, 1 for front/selfie camera)",
                    },
                    "prompt": {
                        "type": "string",
                        "description": "Question or prompt to analyze the captured scene",
                    },
                },
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        camera_id = int(params.get("camera_id", 0))
        prompt = str(params.get("prompt", "Describe what you see in this photo captured by the Android camera.")).strip()
        out_file = "/tmp/jarvis_cam.jpg"

        if os.path.exists(out_file):
            try:
                os.remove(out_file)
            except OSError:
                pass

        cmd = ["termux-camera-photo", "-c", str(camera_id), out_file]
        ok, msg = _run_termux_cmd(cmd, timeout=12.0)
        if not ok or not os.path.exists(out_file) or os.path.getsize(out_file) == 0:
            return ToolResult(tool_name=self.spec.name, content=f"Camera photo capture failed: {msg}. Ensure Termux:API camera permission is granted.", success=False)

        gemini_key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
        if gemini_key and gemini_key != "your_gemini_api_key_here":
            try:
                import base64
                import httpx
                with open(out_file, "rb") as f:
                    b64_img = base64.b64encode(f.read()).decode("utf-8")

                payload = {
                    "contents": [
                        {
                            "role": "user",
                            "parts": [
                                {"text": prompt},
                                {
                                    "inline_data": {
                                        "mime_type": "image/jpeg",
                                        "data": b64_img,
                                    }
                                }
                            ]
                        }
                    ]
                }
                api_url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={gemini_key}"
                with httpx.Client(timeout=30.0) as client:
                    resp = client.post(api_url, json=payload)
                    if resp.status_code == 200:
                        data = resp.json()
                        candidates = data.get("candidates", [])
                        if candidates:
                            parts = candidates[0].get("content", {}).get("parts", [])
                            text_parts = [p.get("text", "") for p in parts if p.get("text")]
                            if text_parts:
                                return ToolResult(
                                    tool_name=self.spec.name,
                                    content=f"Camera photo captured ({out_file}).\nVisual Analysis: " + "\n".join(text_parts),
                                    success=True,
                                    data={"image_path": out_file},
                                )
            except Exception as e:
                logger.debug("Gemini vision analysis failed: %s", e)

        # Fallback to OpenRouter multimodal vision if available
        openrouter_key = os.environ.get("OPENROUTER_API_KEY")
        if openrouter_key:
            try:
                import base64
                import httpx
                with open(out_file, "rb") as f:
                    b64_img = base64.b64encode(f.read()).decode("utf-8")

                data_uri = f"data:image/jpeg;base64,{b64_img}"
                vision_model = os.environ.get("DEFAULT_MODEL") or "google/gemma-4-31b-it:free"
                payload = {
                    "model": vision_model,
                    "messages": [
                        {
                            "role": "user",
                            "content": [
                                {"type": "text", "text": prompt},
                                {"type": "image_url", "image_url": {"url": data_uri}}
                            ]
                        }
                    ]
                }
                headers = {
                    "Authorization": f"Bearer {openrouter_key}",
                    "Content-Type": "application/json"
                }
                with httpx.Client(timeout=30.0) as client:
                    resp = client.post("https://openrouter.ai/api/v1/chat/completions", json=payload, headers=headers)
                    if resp.status_code == 200:
                        data = resp.json()
                        choices = data.get("choices", [])
                        if choices:
                            text = choices[0].get("message", {}).get("content", "")
                            if text:
                                return ToolResult(
                                    tool_name=self.spec.name,
                                    content=f"Camera photo captured ({out_file}).\nVisual Analysis: {text}",
                                    success=True,
                                    data={"image_path": out_file},
                                )
            except Exception as e:
                logger.debug("OpenRouter vision analysis failed: %s", e)

        return ToolResult(
            tool_name=self.spec.name,
            content=f"Photo captured successfully: {out_file}",
            success=True,
            data={"image_path": out_file},
        )


@ToolRegistry.register("android_gps_location")
class AndroidGpsLocationTool(BaseTool):
    """Retrieve live GPS coordinates and location information."""

    tool_id = "android_gps_location"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_gps_location",
            description="Get current live GPS coordinates, altitude, accuracy, and Google Maps link.",
            parameters={"type": "object", "properties": {}},
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        ok, out = _run_termux_cmd(["termux-location", "-p", "network"], timeout=10.0)
        if not ok or not out or "{" not in out:
            ok, out = _run_termux_cmd(["termux-location", "-p", "gps"], timeout=10.0)
        if not ok or not out or "{" not in out:
            ok, out = _run_termux_cmd(["termux-location"], timeout=10.0)

        if ok and out and "{" in out:
            try:
                data = json.loads(out)
                lat = data.get("latitude")
                lon = data.get("longitude")
                alt = data.get("altitude", "N/A")
                acc = data.get("accuracy", "N/A")
                prov = data.get("provider", "network")
                maps_link = f"https://www.google.com/maps?q={lat},{lon}"
                return ToolResult(
                    tool_name=self.spec.name,
                    content=(
                        f"Current Location:\n"
                        f"• Latitude: {lat}\n"
                        f"• Longitude: {lon}\n"
                        f"• Altitude: {alt} m\n"
                        f"• Accuracy: {acc} m (Provider: {prov})\n"
                        f"• Google Maps: {maps_link}"
                    ),
                    success=True,
                )
            except Exception:
                return ToolResult(tool_name=self.spec.name, content=f"Location: {out}", success=True)
        return ToolResult(tool_name=self.spec.name, content=f"Could not retrieve location. Ensure Termux:API location permission is granted. Error: {out}", success=False)


@ToolRegistry.register("android_set_alarm")
class AndroidSetAlarmTool(BaseTool):
    """Set a native Android system alarm."""

    tool_id = "android_set_alarm"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_set_alarm",
            description="Set a native Android clock alarm for a specific hour and minute.",
            parameters={
                "type": "object",
                "properties": {
                    "hour": {
                        "type": "integer",
                        "description": "Alarm hour in 24-hour format (0-23)",
                    },
                    "minute": {
                        "type": "integer",
                        "description": "Alarm minute (0-59)",
                    },
                    "message": {
                        "type": "string",
                        "description": "Alarm label/message (default: 'Jarvis Alarm')",
                    },
                },
                "required": ["hour", "minute"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        hour = int(params.get("hour", 0))
        minute = int(params.get("minute", 0))
        message = str(params.get("message", "Jarvis Alarm")).strip()

        cmd = [
            "am", "start", "--user", "0",
            "-a", "android.intent.action.SET_ALARM",
            "--ei", "android.intent.extra.alarm.HOUR", str(hour),
            "--ei", "android.intent.extra.alarm.MINUTES", str(minute),
            "--es", "android.intent.extra.alarm.MESSAGE", message,
            "--ez", "android.intent.extra.alarm.SKIP_UI", "true",
        ]
        ok, msg = _run_termux_cmd(cmd)
        if ok and "Error:" not in msg:
            return ToolResult(tool_name=self.spec.name, content=f"Alarm set for {hour:02d}:{minute:02d} ('{message}')", success=True)
        return ToolResult(tool_name=self.spec.name, content=f"Failed to set alarm: {msg}", success=False)


@ToolRegistry.register("android_wifi_control")
class AndroidWifiControlTool(BaseTool):
    """Toggle Wi-Fi and inspect connection status."""

    tool_id = "android_wifi_control"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_wifi_control",
            description="Toggle Android Wi-Fi state (enable/disable) and inspect connection information.",
            parameters={
                "type": "object",
                "properties": {
                    "enable": {
                        "type": "boolean",
                        "description": "True to enable Wi-Fi, False to disable, omit to inspect status only",
                    },
                    "action": {
                        "type": "string",
                        "description": "Optional action string: 'enable', 'disable', 'info', or 'status'",
                    },
                },
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        enable = params.get("enable")
        action = str(params.get("action", "")).strip().lower()

        if action in ("enable", "true", "on") and enable is None:
            enable = True
        elif action in ("disable", "false", "off") and enable is None:
            enable = False
        elif isinstance(enable, str):
            enable = enable.lower() in ("true", "1", "yes", "on")

        changes = []
        if enable is not None:
            bool_str = "true" if enable else "false"
            ok, msg = _run_termux_cmd(["termux-wifi-enable", bool_str])
            action_str = "Enabled" if enable else "Disabled"
            if ok:
                changes.append(f"Wi-Fi {action_str}")
            else:
                changes.append(f"Failed to {action_str.lower()} Wi-Fi: {msg}")

        ok, out = _run_termux_cmd(["termux-wifi-connectioninfo"])
        info_str = ""
        if ok and out:
            try:
                info = json.loads(out)
                ssid = info.get("ssid", "<unknown>")
                ip = info.get("ip", "0.0.0.0")
                speed = info.get("link_speed_mbps", "N/A")
                rssi = info.get("rssi", "N/A")
                info_str = f"SSID: {ssid} | IP: {ip} | Speed: {speed} Mbps | RSSI: {rssi} dBm"
            except Exception:
                info_str = out
        return ToolResult(
            tool_name=self.spec.name,
            content="\n".join(changes + ([info_str] if info_str else ["Wi-Fi status queried"])),
            success=True,
        )


@ToolRegistry.register("android_web_search")
class AndroidWebSearchTool(BaseTool):
    """Perform a web search or YouTube search on the Android device."""

    tool_id = "android_web_search"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_web_search",
            description="Search the web (Google) or YouTube directly on the Android device.",
            parameters={
                "type": "object",
                "properties": {
                    "query": {
                        "type": "string",
                        "description": "Search query text",
                    },
                    "engine": {
                        "type": "string",
                        "enum": ["google", "youtube"],
                        "description": "Search engine ('google' or 'youtube')",
                    },
                },
                "required": ["query"],
            },
            category="android",
        )

    def execute(self, **params: Any) -> ToolResult:
        from urllib.parse import quote
        query = str(params.get("query", "")).strip()
        engine = str(params.get("engine", "google")).strip().lower()

        if not query:
            return ToolResult(tool_name=self.spec.name, content="Query is required", success=False)

        q = quote(query)
        url = f"https://www.youtube.com/results?search_query={q}" if engine == "youtube" else f"https://www.google.com/search?q={q}"

        ok, msg = _run_termux_cmd(["am", "start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", url])
        if ok and "Error:" not in msg:
            return ToolResult(tool_name=self.spec.name, content=f"Opened {engine.capitalize()} search for '{query}' ({url})", success=True)
        return ToolResult(tool_name=self.spec.name, content=f"Failed to open search: {msg}", success=False)


__all__ = [
    "AndroidBatteryTool",
    "AndroidCameraCaptureTool",
    "AndroidClipboardGetTool",
    "AndroidClipboardSetTool",
    "AndroidContactsSearchTool",
    "AndroidGpsLocationTool",
    "AndroidIntentTool",
    "AndroidLaunchAppTool",
    "AndroidMediaPlayerTool",
    "AndroidNotificationTool",
    "AndroidPhoneCallTool",
    "AndroidSendEmailTool",
    "AndroidSetAlarmTool",
    "AndroidSmsInboxTool",
    "AndroidSmsSendTool",
    "AndroidTelegramActionTool",
    "AndroidToastTool",
    "AndroidTorchTool",
    "AndroidVibrateTool",
    "AndroidVolumeTool",
    "AndroidWebSearchTool",
    "AndroidWifiControlTool",
]
