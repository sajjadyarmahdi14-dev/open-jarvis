"""``jarvis ui`` — launch the Jarvis Mobile Web UI on Android."""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

import click
import httpx
from rich.console import Console

logger = logging.getLogger(__name__)

SERVER_URL = "http://127.0.0.1:8765"


def _acquire_wake_lock() -> None:
    """Acquire Termux wake-lock to keep background server alive."""
    wake_lock = shutil.which("termux-wake-lock", path="/data/data/com.termux/files/usr/bin:/system/bin:" + os.environ.get("PATH", ""))
    if wake_lock:
        try:
            subprocess.run([wake_lock], capture_output=True, timeout=3.0, check=False)
        except Exception:
            pass


def _is_server_running(port: int = 8765) -> bool:
    """Check if the FastAPI web server is already listening."""
    try:
        with httpx.Client(timeout=3.0) as client:
            resp = client.get(f"http://127.0.0.1:{port}/api/health")
            return resp.status_code == 200
    except Exception:
        return False


def _open_url(url: str) -> None:
    """Open URL in Android browser via termux-open-url or am start."""
    env = dict(os.environ)
    env["PATH"] = f"/data/data/com.termux/files/usr/bin:/system/bin:{env.get('PATH', '')}"

    termux_open = shutil.which("termux-open-url", path=env["PATH"])
    if termux_open:
        try:
            res = subprocess.run([termux_open, url], capture_output=True, timeout=5.0, env=env, check=False)
            if res.returncode == 0:
                return
        except Exception:
            pass

    am_bin = shutil.which("am", path=env["PATH"])
    if am_bin:
        try:
            res = subprocess.run([am_bin, "start", "--user", "0", "-a", "android.intent.action.VIEW", "-d", url], capture_output=True, timeout=5.0, env=env, check=False)
            if res.returncode == 0:
                return
        except Exception:
            pass

    import webbrowser
    webbrowser.open(url)


def ensure_widget_shortcut() -> None:
    """Create a Termux:Widget shortcut script at ~/.shortcuts/Jarvis."""
    shortcuts_dir = Path.home() / ".shortcuts"
    try:
        shortcuts_dir.mkdir(parents=True, exist_ok=True)
        shortcut_file = shortcuts_dir / "Jarvis"

        termux_bash = "/data/data/com.termux/files/usr/bin/bash"
        bash_path = termux_bash if Path(termux_bash).exists() else "/bin/bash"

        script_content = f"""#!{bash_path}
# Termux:Widget Shortcut for Jarvis Mobile
export PATH="/data/data/com.termux/files/usr/bin:/system/bin:$PATH"
REPO_DIR="/data/data/com.termux/files/home/projects/open-jarvis"
if [ -d "$REPO_DIR" ]; then
    cd "$REPO_DIR"
    export PYTHONPATH=src
    python3 -m openjarvis.cli ui
fi
"""
        shortcut_file.write_text(script_content)
        shortcut_file.chmod(0o755)
    except Exception as exc:
        logger.debug("Could not create widget shortcut: %s", exc)


@click.command(name="ui")
@click.option("--port", default=8765, help="Port to bind the mobile UI server.")
@click.option("--no-browser", is_flag=True, default=False, help="Do not open the browser automatically.")
def ui(port: int, no_browser: bool) -> None:
    """Launch the Jarvis Mobile Web UI & local FastAPI backend."""
    console = Console()
    console.print("[cyan bold]Jarvis Mobile Assistant[/cyan bold]")

    # 1. Acquire wake lock
    _acquire_wake_lock()

    # 2. Ensure Termux:Widget shortcut
    ensure_widget_shortcut()

    # 3. Check if server already running
    url = f"http://127.0.0.1:{port}"
    if _is_server_running(port):
        console.print(f"[green]✓[/green] Jarvis server already running at [cyan]{url}[/cyan]")
    else:
        console.print(f"[dim]Starting Jarvis server in background on {url}...[/dim]")
        repo_src = str(Path(__file__).resolve().parent.parent.parent)
        env = dict(os.environ)
        current_pythonpath = env.get("PYTHONPATH", "")
        env["PYTHONPATH"] = f"{repo_src}:{current_pythonpath}" if current_pythonpath else repo_src

        server_cmd = [
            sys.executable,
            "-c",
            f"from openjarvis.web.server import run_server; run_server(port={port})",
        ]

        # Launch detached server process
        subprocess.Popen(
            server_cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            env=env,
            start_new_session=True,
        )

        # Wait up to 5 seconds for server to respond
        started = False
        for _ in range(25):
            time.sleep(0.2)
            if _is_server_running(port):
                started = True
                break

        if started:
            console.print(f"[green bold]✓ Server online:[/green bold] [cyan underline]{url}[/cyan underline]")
        else:
            console.print("[yellow]Server started in background (starting up...)[/yellow]")

    # 4. Open in Android browser
    if not no_browser:
        console.print(f"[cyan]Opening mobile browser to {url}...[/cyan]")
        _open_url(url)

    console.print(
        "\n[dim]Tips:\n"
        " • Add to Home Screen in Chrome/Browser for full-screen PWA experience.\n"
        " • Tap widget on home screen (~/.shortcuts/Jarvis) to launch instantly.\n"
        f" • Stop server: pkill -f 'openjarvis.web.server'[/dim]"
    )
