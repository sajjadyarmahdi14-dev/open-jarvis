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

DEFAULT_TIMEOUT = float(os.environ.get("TERMUX_API_TIMEOUT", 8.0))


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
    "telegram": "org.telegram.messenger",
    "whatsapp": "com.whatsapp",
    "youtube": "com.google.android.youtube",
    "chrome": "com.android.chrome",
    "google chrome": "com.android.chrome",
    "browser": "com.android.chrome",
    "firefox": "org.mozilla.firefox",
    "settings": "com.android.settings",
    "camera": "com.android.camera",
    "termux": "com.termux",
    "spotify": "com.spotify.music",
    "instagram": "com.instagram.android",
    "twitter": "com.twitter.android",
    "x": "com.twitter.android",
    "gmail": "com.google.android.gm",
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
    "discord": "com.discord",
    "tiktok": "com.zhiliaoapp.musically",
    "facebook": "com.facebook.katana",
    "messenger": "com.facebook.orca",
    "reddit": "com.reddit.frontpage",
    "netflix": "com.netflix.mediaclient",
    "uber": "com.ubercab",
}


@ToolRegistry.register("android_launch_app")
class AndroidLaunchAppTool(BaseTool):
    """Launch an Android application by common name or package identifier."""

    tool_id = "android_launch_app"

    @property
    def spec(self) -> ToolSpec:
        return ToolSpec(
            name="android_launch_app",
            description=(
                "Launch an Android application by its common name (e.g. 'Telegram', 'WhatsApp', "
                "'YouTube', 'Chrome', 'Settings', 'Camera', 'Termux', 'Spotify', 'Instagram', "
                "'Gmail', 'Maps', 'Calculator') or package name (e.g. 'org.telegram.messenger')."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "app": {
                        "type": "string",
                        "description": "Common name of the app (e.g. 'Telegram', 'YouTube', 'Settings', 'Camera') or package name",
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
        app_name = str(params.get("app", "")).strip()
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

        if app_lower in COMPONENTS:
            comp = COMPONENTS[app_lower]
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


__all__ = [
    "AndroidBatteryTool",
    "AndroidClipboardGetTool",
    "AndroidClipboardSetTool",
    "AndroidIntentTool",
    "AndroidLaunchAppTool",
    "AndroidMediaPlayerTool",
    "AndroidNotificationTool",
    "AndroidToastTool",
    "AndroidTorchTool",
    "AndroidVibrateTool",
    "AndroidVolumeTool",
]
