"""Persistent configuration and .env manager for OpenJarvis.

Handles auto-loading .env from multiple candidate paths, persistent JSON
settings in ~/.openjarvis/config.json, masked key diagnostics, and hot-reloading.
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

logger = logging.getLogger(__name__)

OPENJARVIS_DIR = Path.home() / ".openjarvis"
CONFIG_JSON_PATH = OPENJARVIS_DIR / "config.json"
DEFAULT_OPENROUTER_MODEL = "meta-llama/llama-3.3-70b-instruct:free"
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"


def get_candidate_env_paths() -> List[Path]:
    """Return all candidate .env paths in order of preference."""
    candidates = [
        Path.cwd() / ".env",
        Path(__file__).resolve().parents[3] / ".env",
        OPENJARVIS_DIR / ".env",
        Path("/data/data/com.termux/files/home/projects/open-jarvis/.env"),
        Path.home() / "projects" / "open-jarvis" / ".env",
    ]
    seen = set()
    result = []
    for c in candidates:
        try:
            resolved = c.resolve()
            if resolved not in seen:
                seen.add(resolved)
                result.append(resolved)
        except Exception:
            if c not in seen:
                seen.add(c)
                result.append(c)
    return result


def parse_env_file(filepath: Path) -> Dict[str, str]:
    """Parse key-value pairs from a .env file without external dependencies."""
    env_vars: Dict[str, str] = {}
    if not filepath.is_file():
        return env_vars

    try:
        content = filepath.read_text(encoding="utf-8", errors="ignore")
        for line in content.splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" in line:
                key, val = line.split("=", 1)
                key = key.strip()
                val = val.strip()
                # Remove surrounding quotes if present
                if (val.startswith('"') and val.endswith('"')) or (val.startswith("'") and val.endswith("'")):
                    val = val[1:-1]
                if key:
                    env_vars[key] = val
    except Exception as exc:
        logger.debug("Failed to parse env file %s: %s", filepath, exc)
    return env_vars


def load_all_env_and_config(override: bool = False) -> Dict[str, str]:
    """Load configuration from .env candidates and ~/.openjarvis/config.json into os.environ."""
    # 1. Try python-dotenv if present
    for path in get_candidate_env_paths():
        if path.is_file():
            try:
                from dotenv import load_dotenv
                load_dotenv(path, override=override)
            except ImportError:
                pass
            # Built-in fallback parser ensures loading even if dotenv is absent
            parsed = parse_env_file(path)
            for k, v in parsed.items():
                if v == "your_gemini_api_key_here":
                    continue
                if override or (k not in os.environ or not os.environ[k]):
                    os.environ[k] = v

    # 2. Load JSON config from ~/.openjarvis/config.json if present
    if CONFIG_JSON_PATH.is_file():
        try:
            data = json.loads(CONFIG_JSON_PATH.read_text(encoding="utf-8"))
            if isinstance(data, dict):
                op_key = data.get("openrouter_api_key") or data.get("OPENROUTER_API_KEY")
                if op_key and (override or not os.environ.get("OPENROUTER_API_KEY")):
                    os.environ["OPENROUTER_API_KEY"] = str(op_key).strip()
                    os.environ["OPENAI_API_KEY"] = str(op_key).strip()
                    os.environ["OPENAI_BASE_URL"] = "https://openrouter.ai/api/v1"

                gem_key = data.get("gemini_api_key") or data.get("GEMINI_API_KEY")
                if gem_key and gem_key != "your_gemini_api_key_here" and (override or not os.environ.get("GEMINI_API_KEY")):
                    os.environ["GEMINI_API_KEY"] = str(gem_key).strip()

                op_model = data.get("openrouter_model") or data.get("OPENROUTER_MODEL")
                if op_model:
                    os.environ["OPENROUTER_MODEL"] = str(op_model).strip()

                gem_model = data.get("gemini_model") or data.get("GEMINI_MODEL")
                if gem_model:
                    os.environ["GEMINI_MODEL"] = str(gem_model).strip()

                def_model = data.get("active_model") or data.get("default_model") or data.get("DEFAULT_MODEL")
                if def_model and (override or not os.environ.get("DEFAULT_MODEL")):
                    os.environ["DEFAULT_MODEL"] = str(def_model).strip()
        except Exception as exc:
            logger.warning("Failed to load %s: %s", CONFIG_JSON_PATH, exc)

    # 3. Synchronize OpenAI key and base URL if OpenRouter is set
    openrouter_key = os.environ.get("OPENROUTER_API_KEY")
    if openrouter_key:
        if not os.environ.get("OPENAI_API_KEY"):
            os.environ["OPENAI_API_KEY"] = openrouter_key
        if not os.environ.get("OPENAI_BASE_URL"):
            os.environ["OPENAI_BASE_URL"] = "https://openrouter.ai/api/v1"

    # 4. Set reasonable model default if missing or paid auto
    current_default = os.environ.get("DEFAULT_MODEL", "")
    if not current_default or current_default == "openrouter/auto":
        if openrouter_key:
            os.environ["DEFAULT_MODEL"] = os.environ.get("OPENROUTER_MODEL") or DEFAULT_OPENROUTER_MODEL
        elif os.environ.get("GEMINI_API_KEY") and os.environ.get("GEMINI_API_KEY") != "your_gemini_api_key_here":
            os.environ["DEFAULT_MODEL"] = os.environ.get("GEMINI_MODEL") or DEFAULT_GEMINI_MODEL
        else:
            os.environ["DEFAULT_MODEL"] = DEFAULT_OPENROUTER_MODEL

    return dict(os.environ)


def mask_key(k: Optional[str]) -> str:
    """Mask an API key for safe display in UI."""
    if not k or len(k) < 8 or k == "your_gemini_api_key_here":
        return ""
    return f"{k[:6]}...{k[-4:]}"


def get_config_status() -> Dict[str, Any]:
    """Return current configuration status with masked keys."""
    load_all_env_and_config(override=False)
    openrouter_key = os.environ.get("OPENROUTER_API_KEY") or ""
    gemini_key = os.environ.get("GEMINI_API_KEY") or ""
    if gemini_key == "your_gemini_api_key_here":
        gemini_key = ""

    openrouter_model = os.environ.get("OPENROUTER_MODEL") or DEFAULT_OPENROUTER_MODEL
    gemini_model = os.environ.get("GEMINI_MODEL") or DEFAULT_GEMINI_MODEL
    default_model = os.environ.get("DEFAULT_MODEL") or ""

    if default_model:
        active_model = default_model
    elif openrouter_key:
        active_model = openrouter_model
    elif gemini_key:
        active_model = gemini_model
    else:
        active_model = DEFAULT_OPENROUTER_MODEL

    active_provider = "gemini" if "gemini" in active_model.lower() else "openrouter"

    return {
        "has_openrouter_key": bool(openrouter_key),
        "openrouter_key_masked": mask_key(openrouter_key),
        "has_gemini_key": bool(gemini_key),
        "gemini_key_masked": mask_key(gemini_key),
        "openrouter_model": openrouter_model,
        "gemini_model": gemini_model,
        "active_model": active_model,
        "active_provider": active_provider,
    }


def save_persistent_config(
    *,
    openrouter_api_key: Optional[str] = None,
    gemini_api_key: Optional[str] = None,
    openrouter_model: Optional[str] = None,
    gemini_model: Optional[str] = None,
    active_model: Optional[str] = None,
) -> Dict[str, Any]:
    """Save persistent configuration to ~/.openjarvis/config.json and .env files."""
    OPENJARVIS_DIR.mkdir(parents=True, exist_ok=True)

    current_data: Dict[str, Any] = {}
    if CONFIG_JSON_PATH.is_file():
        try:
            current_data = json.loads(CONFIG_JSON_PATH.read_text(encoding="utf-8"))
        except Exception:
            current_data = {}

    if openrouter_api_key is not None:
        key_str = openrouter_api_key.strip()
        # Avoid saving if user sent back the masked key string e.g. "sk-or-...2afb"
        if key_str and not key_str.startswith("sk-or-..."):
            current_data["openrouter_api_key"] = key_str
            os.environ["OPENROUTER_API_KEY"] = key_str
            os.environ["OPENAI_API_KEY"] = key_str
            os.environ["OPENAI_BASE_URL"] = "https://openrouter.ai/api/v1"

    if gemini_api_key is not None:
        gem_str = gemini_api_key.strip()
        if gem_str and not gem_str.endswith("..."):
            current_data["gemini_api_key"] = gem_str
            os.environ["GEMINI_API_KEY"] = gem_str

    if openrouter_model is not None and openrouter_model.strip():
        current_data["openrouter_model"] = openrouter_model.strip()
        os.environ["OPENROUTER_MODEL"] = openrouter_model.strip()

    if gemini_model is not None and gemini_model.strip():
        current_data["gemini_model"] = gemini_model.strip()
        os.environ["GEMINI_MODEL"] = gemini_model.strip()

    if active_model is not None and active_model.strip():
        current_data["active_model"] = active_model.strip()
        os.environ["DEFAULT_MODEL"] = active_model.strip()

    # Write config.json
    CONFIG_JSON_PATH.write_text(json.dumps(current_data, indent=2), encoding="utf-8")

    # Update primary .env files if possible
    env_targets = [
        OPENJARVIS_DIR / ".env",
        Path(__file__).resolve().parents[3] / ".env",
    ]
    for env_path in env_targets:
        try:
            env_vars = parse_env_file(env_path) if env_path.is_file() else {}
            if "openrouter_api_key" in current_data:
                env_vars["OPENROUTER_API_KEY"] = current_data["openrouter_api_key"]
                env_vars["OPENAI_API_KEY"] = current_data["openrouter_api_key"]
                env_vars["OPENAI_BASE_URL"] = "https://openrouter.ai/api/v1"
            if "gemini_api_key" in current_data:
                env_vars["GEMINI_API_KEY"] = current_data["gemini_api_key"]
            if "openrouter_model" in current_data:
                env_vars["OPENROUTER_MODEL"] = current_data["openrouter_model"]
            if "active_model" in current_data:
                env_vars["DEFAULT_MODEL"] = current_data["active_model"]
            elif "default_model" in current_data:
                env_vars["DEFAULT_MODEL"] = current_data["default_model"]

            # Serialize lines
            lines = [f"{k}={v}" for k, v in env_vars.items()]
            env_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        except Exception as exc:
            logger.debug("Could not write to %s: %s", env_path, exc)

    return get_config_status()
