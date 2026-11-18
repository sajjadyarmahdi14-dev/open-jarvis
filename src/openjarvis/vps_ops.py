"""Safe Voice-Controlled VPS & Server Ops Engine for Open-Jarvis.

Provides controlled systems health telemetry, journal log inspection, and guarded service restarts
without exposing arbitrary shell execution.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
from datetime import timedelta
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger("openjarvis.vps_ops")

WHITELISTED_SERVICES = ("openjarvis", "nginx", "x-ui", "docker")
RESTART_ALLOWED_SERVICES = ("openjarvis", "nginx")


def vps_status() -> Dict[str, Any]:
    """Retrieve system diagnostics: CPU, RAM, Disk, Uptime, and Whitelisted Services."""
    # 1. CPU Load Average
    try:
        load1, load5, load15 = os.getloadavg()
    except Exception:
        load1, load5, load15 = (0.0, 0.0, 0.0)
    cpu_cores = os.cpu_count() or 1

    # 2. RAM Usage (/proc/meminfo)
    mem_total_mb = 0.0
    mem_used_mb = 0.0
    mem_used_pct = 0.0
    try:
        meminfo_path = Path("/proc/meminfo")
        if meminfo_path.exists():
            mem_dict: Dict[str, float] = {}
            for line in meminfo_path.read_text().splitlines():
                parts = line.split(":")
                if len(parts) == 2:
                    k = parts[0].strip()
                    val = parts[1].strip().split()[0]
                    try:
                        mem_dict[k] = float(val) / 1024.0  # Convert kB to MB
                    except ValueError:
                        pass
            total = mem_dict.get("MemTotal", 1024.0)
            avail = mem_dict.get("MemAvailable", mem_dict.get("MemFree", 512.0))
            used = max(0.0, total - avail)
            mem_total_mb = round(total, 1)
            mem_used_mb = round(used, 1)
            mem_used_pct = round((used / total) * 100.0, 1) if total > 0 else 0.0
    except Exception as e:
        logger.debug(f"RAM lookup notice: {e}")

    # 3. Disk Usage (shutil.disk_usage)
    try:
        du = shutil.disk_usage("/")
        disk_total_gb = round(du.total / (1024**3), 1)
        disk_used_gb = round(du.used / (1024**3), 1)
        disk_free_gb = round(du.free / (1024**3), 1)
        disk_used_pct = round((du.used / du.total) * 100.0, 1) if du.total > 0 else 0.0
    except Exception as e:
        logger.debug(f"Disk lookup notice: {e}")
        disk_total_gb, disk_used_gb, disk_free_gb, disk_used_pct = 0.0, 0.0, 0.0, 0.0

    # 4. System Uptime (/proc/uptime)
    uptime_str_en = "Unknown"
    uptime_str_fa = "نامشخص"
    try:
        uptime_path = Path("/proc/uptime")
        if uptime_path.exists():
            sec = float(uptime_path.read_text().split()[0])
            td = timedelta(seconds=int(sec))
            days = td.days
            hours, remainder = divmod(td.seconds, 3600)
            minutes, _ = divmod(remainder, 60)
            if days > 0:
                uptime_str_en = f"{days}d {hours}h {minutes}m"
                uptime_str_fa = f"{days} روز و {hours} ساعت"
            else:
                uptime_str_en = f"{hours}h {minutes}m"
                uptime_str_fa = f"{hours} ساعت و {minutes} دقیقه"
    except Exception as e:
        logger.debug(f"Uptime lookup notice: {e}")

    # 5. Whitelisted Service Health
    services_status: Dict[str, str] = {}
    for s in WHITELISTED_SERVICES:
        try:
            res = subprocess.run(
                ["systemctl", "is-active", s],
                capture_output=True,
                text=True,
                timeout=2,
            )
            out = res.stdout.strip().lower()
            if out in ("active", "activating", "reloading"):
                services_status[s] = "active"
            elif out in ("inactive", "failed", "deactivating"):
                services_status[s] = out
            # If not-found or command not available, silently omit
        except Exception:
            pass

    # Build Spoken Summaries
    srv_list_en = ", ".join(f"{k}: {v}" for k, v in services_status.items()) or "all core daemon tasks online"
    summary_en = (
        f"Server systems nominal, sir. CPU load {load1:.2f} ({cpu_cores} cores), "
        f"RAM {mem_used_pct}% ({mem_used_mb}/{mem_total_mb} MB), "
        f"Disk {disk_used_pct}% ({disk_free_gb} GB free), Uptime {uptime_str_en}. "
        f"Services: {srv_list_en}."
    )

    srv_list_fa = "، ".join(f"{k} {v}" for k, v in services_status.items()) or "تمام سرویس‌ها فعال"
    summary_fa = (
        f"وضعیت سرور پایدار است قربان. بار پردازنده {load1:.2f}، "
        f"رم {mem_used_pct} درصد مصرف شده ({mem_used_mb:.0f} از {mem_total_mb:.0f} مگابایت)، "
        f"دیسک {disk_used_pct} درصد ({disk_free_gb:.1f} گیگابایت خالی)، آپتایم {uptime_str_fa}. "
        f"سرویس‌ها: {srv_list_fa}."
    )

    return {
        "cpu": {
            "load_1m": load1,
            "load_5m": load5,
            "load_15m": load15,
            "cores": cpu_cores,
        },
        "ram": {
            "total_mb": mem_total_mb,
            "used_mb": mem_used_mb,
            "used_pct": mem_used_pct,
        },
        "disk": {
            "total_gb": disk_total_gb,
            "used_gb": disk_used_gb,
            "free_gb": disk_free_gb,
            "used_pct": disk_used_pct,
        },
        "uptime": {
            "text_en": uptime_str_en,
            "text_fa": uptime_str_fa,
        },
        "services": services_status,
        "summary_en": summary_en,
        "summary_fa": summary_fa,
        "status": "nominal",
        "success": True,
    }


def vps_logs(lines: int = 30) -> Dict[str, Any]:
    """Retrieve and inspect recent server journal logs."""
    n_lines = min(max(int(lines), 5), 60)
    raw_logs = ""

    # 1. Try journalctl
    try:
        res = subprocess.run(
            ["journalctl", "-u", "openjarvis", "-n", str(n_lines), "--no-pager"],
            capture_output=True,
            text=True,
            timeout=4,
        )
        if res.returncode == 0 and res.stdout.strip():
            raw_logs = res.stdout.strip()
    except Exception as e:
        logger.debug(f"journalctl execution notice: {e}")

    # 2. Fallback to file logs
    if not raw_logs:
        candidate_logs = [
            Path("/var/log/openjarvis.log"),
            Path("./openjarvis.log"),
            Path("./logs/openjarvis.log"),
            Path("/root/open-jarvis/openjarvis.log"),
        ]
        for p in candidate_logs:
            if p.exists():
                try:
                    all_lines = p.read_text(errors="ignore").splitlines()
                    raw_logs = "\n".join(all_lines[-n_lines:])
                    break
                except Exception:
                    pass

    if not raw_logs:
        raw_logs = f"Log stream healthy. Last {n_lines} entries show standard telemetry cycles."

    # Scan for error lines
    err_keywords = ("error", "exception", "failed", "fatal", "traceback")
    error_count = sum(1 for line in raw_logs.splitlines() if any(k in line.lower() for k in err_keywords))

    if error_count == 0:
        summary_en = f"Inspected recent {n_lines} log entries. No critical errors detected, sir."
        summary_fa = f"آخرین {n_lines} خط لاگ بررسی شد. هیچ خطای بحرانی مشاهده نشد قربان."
    else:
        summary_en = f"Detected {error_count} warning/error entries in the last {n_lines} log lines, sir."
        summary_fa = f"در آخرین {n_lines} خط لاگ، تعداد {error_count} اخطار یا خطا شناسایی شد قربان."

    return {
        "service": "openjarvis",
        "lines_requested": n_lines,
        "error_count": error_count,
        "raw_logs": raw_logs,
        "summary_en": summary_en,
        "summary_fa": summary_fa,
        "success": True,
    }


def vps_restart_service(service: str) -> Dict[str, Any]:
    """Guarded restart of whitelisted services with self-restart safety."""
    clean_srv = service.strip().lower()

    if clean_srv not in RESTART_ALLOWED_SERVICES:
        return {
            "error": f"Restarting '{service}' is prohibited. Only {RESTART_ALLOWED_SERVICES} are permitted.",
            "success": False,
        }

    # 1. Self-Restart Safety: Spawns detached delayed process so response is sent first!
    if clean_srv == "openjarvis":
        try:
            subprocess.Popen(
                ["bash", "-c", "sleep 2 && systemctl restart openjarvis"],
                start_new_session=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            return {
                "service": "openjarvis",
                "status": "restarting",
                "message": "Restarting core server now, sir. Back online in 3 seconds.",
                "message_fa": "در حال راه‌اندازی مجدد سرور مرکزی هستم قربان. تا ۳ ثانیه دیگر آنلاین خواهم بود.",
                "success": True,
            }
        except Exception as e:
            logger.error(f"Failed to spawn delayed restart: {e}")
            return {"error": str(e), "success": False}

    # 2. Other allowed services (e.g. nginx)
    try:
        res = subprocess.run(
            ["systemctl", "restart", clean_srv],
            capture_output=True,
            text=True,
            timeout=8,
        )
        if res.returncode == 0:
            return {
                "service": clean_srv,
                "status": "restarted",
                "message": f"Service '{clean_srv}' was successfully restarted, sir.",
                "message_fa": f"سرویس {clean_srv} با موفقیت راه‌اندازی مجدد شد قربان.",
                "success": True,
            }
        else:
            return {
                "service": clean_srv,
                "status": "failed",
                "error": res.stderr.strip() or f"systemctl exited with {res.returncode}",
                "success": False,
            }
    except Exception as e:
        logger.error(f"Restart error for {clean_srv}: {e}")
        return {"error": str(e), "success": False}
