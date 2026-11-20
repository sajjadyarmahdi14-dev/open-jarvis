"""Pure-httpx REST client for Google Gemini and OpenAI/OpenRouter APIs.

Provides direct HTTP/2 & SSE streaming implementations with zero dependencies
on the official google-genai or openai SDKs, enabling lightweight execution
on Android / Termux and low-resource environments.
"""

from __future__ import annotations

import json
import logging
import os
import time
import uuid
from collections.abc import AsyncIterator, Sequence
from typing import Any, Dict, List, Optional, Tuple

import httpx

from openjarvis.core.types import Message
from openjarvis.engine._base import (
    EngineConnectionError,
    EngineContextLengthError,
    estimate_prompt_tokens,
    looks_like_context_length_error,
    messages_to_dicts,
)
from openjarvis.engine._stubs import StreamChunk

logger = logging.getLogger(__name__)

GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
OPENROUTER_API_BASE = "https://openrouter.ai/api/v1"


# ---------------------------------------------------------------------------
# Google Gemini REST Helpers
# ---------------------------------------------------------------------------


def normalize_openai_tools(tools: Any) -> List[Dict[str, Any]]:
    """Convert any tool definitions (BaseTool, ToolSpec, dict) to standard OpenAI tool dicts."""
    if not tools:
        return []
    normalized: List[Dict[str, Any]] = []
    for item in tools:
        if hasattr(item, "to_openai_function") and callable(item.to_openai_function):
            normalized.append(item.to_openai_function())
        elif isinstance(item, dict):
            if "type" in item and "function" in item:
                normalized.append(item)
            elif "name" in item:
                normalized.append({
                    "type": "function",
                    "function": {
                        "name": item.get("name"),
                        "description": item.get("description", ""),
                        "parameters": item.get("parameters", {"type": "object", "properties": {}}),
                    },
                })
        elif hasattr(item, "spec"):
            spec = getattr(item, "spec")
            normalized.append({
                "type": "function",
                "function": {
                    "name": getattr(spec, "name", str(item)),
                    "description": getattr(spec, "description", ""),
                    "parameters": getattr(spec, "parameters", {"type": "object", "properties": {}}),
                },
            })
    return normalized


def convert_tools_to_gemini(
    openai_tools: Sequence[Any],
) -> List[Dict[str, Any]]:
    """Convert OpenAI-format function tools to Gemini function declarations."""
    normalized = normalize_openai_tools(openai_tools)
    declarations = []
    for tool in normalized:
        func = tool.get("function", {})
        declarations.append(
            {
                "name": func.get("name", ""),
                "description": func.get("description", ""),
                "parameters": func.get("parameters", {}),
            }
        )
    return declarations


def build_gemini_contents(
    messages: Sequence[Message],
    thought_sigs: Optional[Dict[str, bytes]] = None,
) -> Tuple[str, List[Dict[str, Any]]]:
    """Convert openjarvis Message sequence into Gemini contents and system text."""
    system_text = ""
    contents: List[Dict[str, Any]] = []

    for m in messages:
        role = m.role.value
        if role == "system":
            system_text = m.content
        elif role == "tool":
            fn_response = {
                "functionResponse": {
                    "name": m.name or "unknown",
                    "response": {"result": m.content},
                }
            }
            # Merge consecutive tool responses into one user turn if previous was user
            if (
                contents
                and contents[-1]["role"] == "user"
                and contents[-1]["parts"]
                and "functionResponse" in contents[-1]["parts"][-1]
            ):
                contents[-1]["parts"].append(fn_response)
            else:
                contents.append({"role": "user", "parts": [fn_response]})
        elif role == "assistant" and m.tool_calls:
            parts: List[Dict[str, Any]] = []
            if m.content:
                parts.append({"text": m.content})
            for tc in m.tool_calls:
                args = tc.arguments
                if isinstance(args, str):
                    try:
                        args = json.loads(args)
                    except (json.JSONDecodeError, TypeError):
                        args = {"input": args}
                fc_part: Dict[str, Any] = {
                    "functionCall": {
                        "name": tc.name,
                        "args": args if isinstance(args, dict) else {},
                    }
                }
                if thought_sigs and tc.id in thought_sigs:
                    fc_part["thoughtSignature"] = thought_sigs[tc.id]
                parts.append(fc_part)
            contents.append({"role": "model", "parts": parts})
        elif role == "assistant":
            contents.append({"role": "model", "parts": [{"text": m.content}]})
        else:
            contents.append({"role": "user", "parts": [{"text": m.content}]})

    return system_text, contents


def build_gemini_payload(
    system_text: str,
    contents: List[Dict[str, Any]],
    temperature: float,
    max_tokens: int,
    tools: Optional[List[Dict[str, Any]]] = None,
    response_format: Optional[Any] = None,
) -> Dict[str, Any]:
    """Construct JSON payload for Gemini generateContent / streamGenerateContent."""
    payload: Dict[str, Any] = {
        "contents": contents,
        "generationConfig": {
            "temperature": temperature,
            "maxOutputTokens": max_tokens,
        },
    }
    if system_text:
        payload["systemInstruction"] = {"parts": [{"text": system_text}]}
    if tools:
        declarations = convert_tools_to_gemini(tools)
        payload["tools"] = [{"functionDeclarations": declarations}]
    if response_format is not None:
        from openjarvis.engine._stubs import ResponseFormat

        if isinstance(response_format, ResponseFormat):
            payload["generationConfig"]["responseMimeType"] = "application/json"
            if response_format.schema:
                payload["generationConfig"]["responseSchema"] = response_format.schema
    return payload


def generate_gemini_rest(
    client: httpx.Client,
    api_key: str,
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 2500,
    thought_sigs: Optional[Dict[str, bytes]] = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """Synchronous Gemini completion via REST."""
    system_text, contents = build_gemini_contents(messages, thought_sigs)
    raw_tools = kwargs.pop("tools", None)
    response_format = kwargs.pop("response_format", None)
    payload = build_gemini_payload(
        system_text=system_text,
        contents=contents,
        temperature=temperature,
        max_tokens=max_tokens,
        tools=raw_tools,
        response_format=response_format,
    )

    url = f"{GEMINI_API_BASE}/{model}:generateContent?key={api_key}"
    t0 = time.monotonic()
    try:
        resp = client.post(url, json=payload)
        resp.raise_for_status()
    except httpx.HTTPStatusError as exc:
        err_msg = exc.response.text
        if looks_like_context_length_error(err_msg):
            raise EngineContextLengthError(f"Gemini context overflow: {err_msg}") from exc
        raise EngineConnectionError(f"Gemini API returned HTTP {exc.response.status_code}: {err_msg}") from exc
    except (httpx.ConnectError, httpx.TimeoutException) as exc:
        raise EngineConnectionError("Failed to connect to Gemini API") from exc

    elapsed = time.monotonic() - t0
    data = resp.json()

    text_parts: List[str] = []
    tool_calls: List[Dict[str, Any]] = []
    candidates = data.get("candidates", [])
    finish_reason = "stop"

    if candidates:
        first = candidates[0]
        finish_reason = first.get("finishReason", "stop").lower()
        parts = first.get("content", {}).get("parts", [])
        tool_idx = 0
        for part in parts:
            if "text" in part and part["text"]:
                text_parts.append(part["text"])
            fc = part.get("functionCall") or part.get("function_call")
            if fc:
                name = fc.get("name", "")
                args = fc.get("args", {})
                tool_id = f"google_{uuid.uuid4().hex[:8]}_{tool_idx}"
                tool_idx += 1
                tc_item = {
                    "id": tool_id,
                    "index": tool_idx - 1,
                    "name": name,
                    "arguments": json.dumps(args),
                }
                sig = part.get("thoughtSignature") or part.get("thought_signature")
                if sig and thought_sigs is not None:
                    thought_sigs[tool_id] = sig
                    tc_item["thought_signature"] = sig
                tool_calls.append(tc_item)

    usage_meta = data.get("usageMetadata", {})
    prompt_tokens = usage_meta.get("promptTokenCount", 0)
    completion_tokens = usage_meta.get("candidatesTokenCount", 0)
    total_tokens = usage_meta.get("totalTokenCount", prompt_tokens + completion_tokens)

    res: Dict[str, Any] = {
        "content": "\n".join(text_parts) if text_parts else "",
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": total_tokens,
        },
        "model": model,
        "finish_reason": finish_reason,
        "ttft": elapsed,
        "cost_usd": 0.0,
    }
    if tool_calls:
        res["tool_calls"] = tool_calls
    return res


async def stream_full_gemini_rest(
    async_client: httpx.AsyncClient,
    api_key: str,
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 2500,
    thought_sigs: Optional[Dict[str, bytes]] = None,
    **kwargs: Any,
) -> AsyncIterator[StreamChunk]:
    """Async SSE streaming for Gemini with full tool_calls support."""
    system_text, contents = build_gemini_contents(messages, thought_sigs)
    raw_tools = kwargs.pop("tools", None)
    response_format = kwargs.pop("response_format", None)
    payload = build_gemini_payload(
        system_text=system_text,
        contents=contents,
        temperature=temperature,
        max_tokens=max_tokens,
        tools=raw_tools,
        response_format=response_format,
    )

    url = f"{GEMINI_API_BASE}/{model}:streamGenerateContent?alt=sse&key={api_key}"
    stream_id = uuid.uuid4().hex[:8]
    tool_idx = 0
    final_usage: Optional[Dict[str, Any]] = None
    finish_reason: Optional[str] = None

    try:
        async with async_client.stream("POST", url, json=payload) as resp:
            if not resp.is_success:
                await resp.aread()
                err_text = resp.text
                if looks_like_context_length_error(err_text):
                    raise EngineContextLengthError(f"Gemini context overflow: {err_text}")
                raise EngineConnectionError(f"Gemini stream returned HTTP {resp.status_code}: {err_text}")

            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                data_str = line[len("data:") :].strip()
                if not data_str or data_str == "[DONE]":
                    break
                try:
                    data = json.loads(data_str)
                except json.JSONDecodeError:
                    continue

                usage_meta = data.get("usageMetadata")
                if usage_meta:
                    p = usage_meta.get("promptTokenCount", 0)
                    c = usage_meta.get("candidatesTokenCount", 0)
                    final_usage = {
                        "prompt_tokens": p,
                        "completion_tokens": c,
                        "total_tokens": usage_meta.get("totalTokenCount", p + c),
                    }

                candidates = data.get("candidates", [])
                if not candidates:
                    continue

                candidate = candidates[0]
                if "finishReason" in candidate:
                    finish_reason = candidate["finishReason"].lower()

                parts = candidate.get("content", {}).get("parts", [])
                calls: List[Dict[str, Any]] = []

                for part in parts:
                    text = part.get("text")
                    if text:
                        yield StreamChunk(content=text)

                    fc = part.get("functionCall") or part.get("function_call")
                    if fc:
                        name = fc.get("name", "")
                        args = fc.get("args", {})
                        tool_id = f"google_{stream_id}_{tool_idx}"
                        tool_idx += 1
                        tool_call = {
                            "index": tool_idx - 1,
                            "id": tool_id,
                            "type": "function",
                            "function": {
                                "name": name,
                                "arguments": json.dumps(args),
                            },
                        }
                        sig = part.get("thoughtSignature") or part.get("thought_signature")
                        if sig and thought_sigs is not None:
                            thought_sigs[tool_id] = sig
                            tool_call["thought_signature"] = sig
                        calls.append(tool_call)

                if calls:
                    yield StreamChunk(tool_calls=calls)

    except (httpx.ConnectError, httpx.TimeoutException) as exc:
        raise EngineConnectionError("Gemini streaming connection failed") from exc

    if finish_reason or final_usage:
        yield StreamChunk(finish_reason=finish_reason, usage=final_usage)


# ---------------------------------------------------------------------------
# OpenAI-Compatible & OpenRouter REST Helpers
# ---------------------------------------------------------------------------

QUOTA_EXHAUSTED_REPLY_FA = (
    "سهمیه روزانه مدل هوش مصنوعی تموم شده قربان؛ ولی تمام دستورات آفلاین گوشی "
    "مثل موزیک، تماس، دوربین و تنظیمات هنوز کامل کار میکنن. میتونید از تنظیمات مدل رو عوض کنید."
)
QUOTA_EXHAUSTED_REPLY_EN = (
    "Daily AI quota is exhausted, sir. Offline device controls remain fully active—you can switch models in Settings."
)


def safe_get_dict(obj: Any, key: str) -> Dict[str, Any]:
    """Safely extract a dict from obj[key], returning {} if missing or not a dict."""
    if not isinstance(obj, dict):
        return {}
    val = obj.get(key)
    return val if isinstance(val, dict) else {}


def safe_get_list(obj: Any, key: str) -> List[Any]:
    """Safely extract a list from obj[key], returning [] if missing or not a list."""
    if not isinstance(obj, dict):
        return []
    val = obj.get(key)
    return val if isinstance(val, list) else []


def safe_extract_message_content(data: Any) -> str:
    """Never-throw safe extractor for LLM message content string."""
    if not isinstance(data, dict):
        return ""
    choices = safe_get_list(data, "choices")
    if not choices:
        return ""
    first = choices[0] if isinstance(choices[0], dict) else {}
    msg = safe_get_dict(first, "message")
    content = msg.get("content")
    if content is None:
        return ""
    return str(content)


def safe_extract_tool_calls(data: Any) -> List[Dict[str, Any]]:
    """Never-throw safe extractor for OpenAI-style tool calls."""
    if not isinstance(data, dict):
        return []
    choices = safe_get_list(data, "choices")
    if not choices:
        return []
    first = choices[0] if isinstance(choices[0], dict) else {}
    msg = safe_get_dict(first, "message")
    raw_calls = safe_get_list(msg, "tool_calls")
    results: List[Dict[str, Any]] = []
    for tc in raw_calls:
        if not isinstance(tc, dict):
            continue
        fn = safe_get_dict(tc, "function")
        args_raw = fn.get("arguments", "{}")
        if isinstance(args_raw, dict):
            args_str = json.dumps(args_raw)
        else:
            args_str = str(args_raw or "{}")
        results.append({
            "id": str(tc.get("id") or ""),
            "name": str(fn.get("name") or ""),
            "arguments": args_str,
        })
    return results


def is_quota_status_or_text(status_code: Optional[int], err_text: str = "") -> bool:
    """Detect if HTTP error or response body represents quota or rate-limit exhaustion."""
    if status_code in (402, 429):
        return True
    t_lower = (err_text or "").lower()
    return any(k in t_lower for k in ("quota", "insufficient_quota", "rate limit", "credits", "free request limit", "exceeded your current quota"))


FREE_MODEL_FALLBACK_POOL: List[str] = [
    "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
    "meta-llama/llama-3.3-70b-instruct:free",
    "google/gemma-3-27b-it:free",
    "google/gemma-3-12b-it:free",
    "google/gemma-3-4b-it:free",
    "qwen/qwen-2.5-72b-instruct:free",
    "mistralai/mistral-small-3.1-24b-instruct:free",
    "deepseek/deepseek-r1:free",
    "deepseek/deepseek-chat:free",
]

CURATED_FREE_MODELS: List[Dict[str, Any]] = [
    {
        "id": "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
        "name": "NVIDIA: Nemotron-3 Nano Omni Reasoning (Free)",
        "context_length": 32768,
        "pricing": "free",
        "description": "Fast multi-modal reasoning engine with low latency.",
    },
    {
        "id": "google/gemma-3-27b-it:free",
        "name": "Google: Gemma 3 27B Instruct (Free)",
        "context_length": 32768,
        "pricing": "free",
        "description": "Google lightweight instruction-tuned model.",
    },
    {
        "id": "meta-llama/llama-3.3-70b-instruct:free",
        "name": "Meta: Llama 3.3 70B Instruct (Free)",
        "context_length": 131072,
        "pricing": "free",
        "description": "State-of-the-art open weights flagship by Meta.",
    },
    {
        "id": "qwen/qwen-2.5-72b-instruct:free",
        "name": "Qwen: Qwen 2.5 72B Instruct (Free)",
        "context_length": 32768,
        "pricing": "free",
        "description": "High intelligence multilingual model by Alibaba.",
    },
    {
        "id": "mistralai/mistral-small-3.1-24b-instruct:free",
        "name": "Mistral: Mistral Small 3.1 24B (Free)",
        "context_length": 32768,
        "pricing": "free",
        "description": "Efficient and powerful instruction model by Mistral AI.",
    },
]

_OPENROUTER_LIVE_FREE_MODELS: List[str] = []
_OPENROUTER_LIVE_MODELS_FETCHED: bool = False


def fetch_detailed_free_models(api_key: Optional[str] = None) -> List[Dict[str, Any]]:
    """Fetch live free models list from OpenRouter with metadata, sorted with curated fallbacks."""
    headers = {}
    effective_key = api_key or os.environ.get("OPENROUTER_API_KEY") or os.environ.get("OPENAI_API_KEY")
    if effective_key:
        headers["Authorization"] = f"Bearer {effective_key}"

    results: List[Dict[str, Any]] = []
    seen_ids = set()

    try:
        with httpx.Client(timeout=6.0) as client:
            resp = client.get("https://openrouter.ai/api/v1/models", headers=headers)
            if resp.is_success:
                data = resp.json().get("data", [])
                for item in data:
                    if not isinstance(item, dict):
                        continue
                    m_id = str(item.get("id", "")).strip()
                    pricing = item.get("pricing") or {}
                    prompt_price = str(pricing.get("prompt", "")).strip()
                    is_free = m_id.endswith(":free") or (prompt_price in ("0", "0.0") and m_id.endswith(":free"))
                    if is_free and m_id != "openrouter/free" and m_id not in seen_ids:
                        seen_ids.add(m_id)
                        results.append({
                            "id": m_id,
                            "name": item.get("name") or m_id,
                            "context_length": item.get("context_length") or 32768,
                            "pricing": "free",
                            "description": item.get("description", "")[:160],
                        })
    except Exception as exc:
        logger.warning("Error fetching live models from OpenRouter: %s", exc)

    # Curated fallbacks/priority items
    curated_map = {c["id"]: c for c in CURATED_FREE_MODELS}
    final_list: List[Dict[str, Any]] = []

    # 1. Add curated models first (updating with live metadata if available)
    for c_id, c_item in curated_map.items():
        matched = next((r for r in results if r["id"] == c_id), None)
        if matched:
            final_list.append(matched)
        else:
            final_list.append(dict(c_item))
        seen_ids.add(c_id)

    # 2. Add remaining live free models discovered from OpenRouter
    for r in results:
        if r["id"] not in curated_map:
            final_list.append(r)

    # Ensure default reasoning model is strictly at the very top
    top_model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"
    final_list.sort(key=lambda x: 0 if x["id"] == top_model else 1)
    return final_list


def get_openrouter_free_models(api_key: Optional[str] = None) -> List[str]:
    """Fetch live free models list from OpenRouter API, cached in memory."""
    global _OPENROUTER_LIVE_FREE_MODELS, _OPENROUTER_LIVE_MODELS_FETCHED
    if _OPENROUTER_LIVE_MODELS_FETCHED and _OPENROUTER_LIVE_FREE_MODELS:
        return list(_OPENROUTER_LIVE_FREE_MODELS)
    detailed = fetch_detailed_free_models(api_key)
    free_ids = [m["id"] for m in detailed if m.get("id")]
    if free_ids:
        _OPENROUTER_LIVE_FREE_MODELS = free_ids
        _OPENROUTER_LIVE_MODELS_FETCHED = True
        return list(_OPENROUTER_LIVE_FREE_MODELS)
    _OPENROUTER_LIVE_MODELS_FETCHED = True
    return list(FREE_MODEL_FALLBACK_POOL)


def generate_openai_compat_rest(
    client: httpx.Client,
    base_url: str,
    api_key: Optional[str],
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 2500,
    extra_headers: Optional[Dict[str, str]] = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """Synchronous OpenAI-compatible completion with multi-model fallback and tool_calls support."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    if extra_headers:
        headers.update(extra_headers)

    raw_tools = kwargs.pop("tools", None)
    payload: Dict[str, Any] = {
        "model": model,
        "messages": messages_to_dicts(messages),
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": False,
        **kwargs,
    }
    if raw_tools:
        norm_tools = normalize_openai_tools(raw_tools)
        if norm_tools:
            payload["tools"] = norm_tools
            if "tool_choice" not in payload:
                payload["tool_choice"] = "auto"
    elif "tools" in payload:
        payload["tools"] = normalize_openai_tools(payload["tools"])
        if "tool_choice" not in payload:
            payload["tool_choice"] = "auto"

    url = f"{base_url.rstrip('/')}/chat/completions"
    t0 = time.monotonic()

    # Prioritized model pool with auto-rotation on 400, 404, 429, 5xx, timeout, or blank reply
    initial_model = "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free" if model == "openrouter/free" else model
    models_to_try = [initial_model]
    if "openrouter" in base_url.lower():
        for pm in FREE_MODEL_FALLBACK_POOL:
            if pm not in models_to_try and pm != "openrouter/free":
                models_to_try.append(pm)
        try:
            live_free = get_openrouter_free_models(api_key)
            for lm in live_free:
                if lm not in models_to_try and lm != "openrouter/free":
                    models_to_try.append(lm)
        except Exception:
            pass
    # Strictly purge any occurrence of openrouter/free
    models_to_try = [m for m in models_to_try if m != "openrouter/free"]
    if not models_to_try:
        models_to_try = ["nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"]

    data = None
    selected_model = initial_model
    last_exc = None
    saw_quota = False

    for candidate in models_to_try:
        cand_payload = dict(payload)
        cand_payload["model"] = candidate
        cand_lower = candidate.lower()
        if "openrouter" in base_url.lower() or any(k in cand_lower for k in ("reasoning", "nemotron", "deepseek", "qwen")):
            if "reasoning" not in cand_payload:
                cand_payload["reasoning"] = {"effort": "low", "exclude": True}
            if cand_payload.get("max_tokens", 2500) < 2500:
                cand_payload["max_tokens"] = 2500
        else:
            cand_payload.pop("reasoning", None)

        try:
            resp = client.post(url, json=cand_payload, headers=headers)
            if is_quota_status_or_text(resp.status_code, resp.text):
                saw_quota = True

            if resp.status_code in (401, 402, 403, 404, 408, 429, 500, 502, 503, 504) and candidate != models_to_try[-1]:
                logger.warning("Model %s returned HTTP %d, rotating to next candidate...", candidate, resp.status_code)
                continue
            if resp.status_code == 400 and "tools" in cand_payload:
                cand_payload.pop("tools", None)
                cand_payload.pop("tool_choice", None)
                resp = client.post(url, json=cand_payload, headers=headers)
                if not resp.is_success and candidate != models_to_try[-1]:
                    logger.warning("Model %s failed without tools (HTTP %d), rotating...", candidate, resp.status_code)
                    continue

            resp.raise_for_status()
            cand_data = resp.json()
            if not isinstance(cand_data, dict):
                if candidate != models_to_try[-1]:
                    continue
                cand_data = {}

            if "error" in cand_data:
                err_text = str(cand_data.get("error") or "")
                if is_quota_status_or_text(resp.status_code, err_text):
                    saw_quota = True
                if candidate != models_to_try[-1]:
                    logger.warning("Model %s returned error body: %s, rotating...", candidate, err_text[:120])
                    continue

            cand_content = safe_extract_message_content(cand_data).strip()
            cand_tool_calls = safe_extract_tool_calls(cand_data)
            if not cand_content and not cand_tool_calls and candidate != models_to_try[-1]:
                logger.warning("Model %s returned blank content and no tool calls, rotating...", candidate)
                continue

            data = cand_data
            selected_model = candidate
            break
        except httpx.HTTPStatusError as exc:
            last_exc = exc
            err_msg = exc.response.text
            if is_quota_status_or_text(exc.response.status_code, err_msg):
                saw_quota = True
            if exc.response.status_code in (400, 401, 402, 403, 404, 408, 429, 500, 502, 503, 504) and candidate != models_to_try[-1]:
                logger.warning("HTTP %d on model %s, rotating...", exc.response.status_code, candidate)
                continue
            if looks_like_context_length_error(err_msg):
                raise EngineContextLengthError(f"Context length overflow: {err_msg}") from exc
            if candidate == models_to_try[-1] and not saw_quota:
                raise EngineConnectionError(f"All models failed. Last error HTTP {exc.response.status_code}: {err_msg}") from exc
        except (httpx.ConnectError, httpx.TimeoutException) as exc:
            last_exc = exc
            if candidate != models_to_try[-1]:
                logger.warning("Timeout/ConnectError on model %s, rotating...", candidate)
                continue
            if not saw_quota:
                raise EngineConnectionError(f"Connection failed to {url}") from exc

    is_fa = any("\u0600" <= c <= "\u06FF" for m in messages for c in (getattr(m, "content", "") or ""))

    if data is None:
        if saw_quota:
            quota_reply = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            return {
                "content": quota_reply,
                "usage": {},
                "model": selected_model,
                "finish_reason": "quota_exhausted",
                "cost_usd": 0.0,
                "ttft": time.monotonic() - t0,
            }
        fallback_reply = (
            "ارتباط با سرور مدل هوش مصنوعی در حال حاضر مقدور نیست قربان؛ اما تمام امکانات آفلاین گوشی در دسترس است."
            if is_fa else
            "Unable to reach the AI model server, sir. All offline device controls remain fully operational."
        )
        return {
            "content": fallback_reply,
            "usage": {},
            "model": selected_model,
            "finish_reason": "error",
            "cost_usd": 0.0,
            "ttft": time.monotonic() - t0,
        }

    elapsed = time.monotonic() - t0
    choices = safe_get_list(data, "choices")
    if not choices:
        if saw_quota:
            quota_reply = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            return {
                "content": quota_reply,
                "usage": safe_get_dict(data, "usage"),
                "model": data.get("model", selected_model),
                "finish_reason": "quota_exhausted",
                "cost_usd": 0.0,
                "ttft": elapsed,
            }
        return {
            "content": "",
            "usage": safe_get_dict(data, "usage"),
            "model": data.get("model", selected_model),
            "finish_reason": "error",
            "cost_usd": 0.0,
            "ttft": elapsed,
        }

    first_choice = choices[0] if (choices and isinstance(choices[0], dict)) else {}
    usage = safe_get_dict(data, "usage")
    prompt_tokens = usage.get("prompt_tokens") or estimate_prompt_tokens(messages)
    completion_tokens = usage.get("completion_tokens", 0) or 0

    content = safe_extract_message_content(data)
    tool_calls = safe_extract_tool_calls(data)

    result: Dict[str, Any] = {
        "content": content,
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
        "model": data.get("model", selected_model),
        "finish_reason": str(first_choice.get("finish_reason") or "stop"),
        "cost_usd": 0.0,
        "ttft": elapsed,
    }

    if tool_calls:
        result["tool_calls"] = tool_calls
    return result


async def stream_full_openai_compat_rest(
    async_client: httpx.AsyncClient,
    base_url: str,
    api_key: Optional[str],
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 2500,
    extra_headers: Optional[Dict[str, str]] = None,
    **kwargs: Any,
) -> AsyncIterator[StreamChunk]:
    """Async SSE streaming for OpenAI-compatible endpoint with rich tool_calls."""
    headers = {"Content-Type": "application/json"}
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"
    if extra_headers:
        headers.update(extra_headers)

    raw_tools = kwargs.pop("tools", None)
    payload: Dict[str, Any] = {
        "model": model,
        "messages": messages_to_dicts(messages),
        "temperature": temperature,
        "max_tokens": max_tokens,
        "stream": True,
        **kwargs,
    }
    # Optimize TTFT (time-to-first-token) and ensure sufficient tokens for reasoning models
    model_lower = model.lower()
    if "openrouter" in base_url.lower() or any(k in model_lower for k in ("reasoning", "nemotron", "deepseek", "qwen")):
        if "reasoning" not in payload:
            payload["reasoning"] = {"effort": "none", "exclude": True}
        if payload.get("max_tokens", 2500) < 2500:
            payload["max_tokens"] = 2500
    if raw_tools:
        norm_tools = normalize_openai_tools(raw_tools)
        if norm_tools:
            payload["tools"] = norm_tools
            if "tool_choice" not in payload:
                payload["tool_choice"] = "auto"
    elif "tools" in payload:
        payload["tools"] = normalize_openai_tools(payload["tools"])
        if "tool_choice" not in payload:
            payload["tool_choice"] = "auto"

    url = f"{base_url.rstrip('/')}/chat/completions"
    accumulated_tool_calls: Dict[int, Dict[str, Any]] = {}

    models_to_try = [model]
    if "openrouter" in base_url.lower():
        for pm in FREE_MODEL_FALLBACK_POOL:
            if pm not in models_to_try and pm != "openrouter/free":
                models_to_try.append(pm)
        try:
            live_free = get_openrouter_free_models(api_key)
            for lm in live_free:
                if lm not in models_to_try and lm != "openrouter/free":
                    models_to_try.append(lm)
        except Exception:
            pass
    models_to_try = [m for m in models_to_try if m != "openrouter/free"]
    if not models_to_try:
        models_to_try = ["nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"]

    stream_started = False
    last_exc = None
    saw_quota = False

    for candidate in models_to_try:
        cand_payload = dict(payload)
        cand_payload["model"] = candidate
        cand_lower = candidate.lower()
        if "openrouter" in base_url.lower() or any(k in cand_lower for k in ("reasoning", "nemotron", "deepseek", "qwen")):
            if "reasoning" not in cand_payload:
                cand_payload["reasoning"] = {"effort": "none", "exclude": True}
            if cand_payload.get("max_tokens", 2500) < 2500:
                cand_payload["max_tokens"] = 2500
        else:
            cand_payload.pop("reasoning", None)

        try:
            async with async_client.stream("POST", url, json=cand_payload, headers=headers, timeout=6.0) as resp:
                if is_quota_status_or_text(resp.status_code):
                    saw_quota = True

                if not resp.is_success:
                    await resp.aread()
                    err_text = resp.text
                    if is_quota_status_or_text(resp.status_code, err_text):
                        saw_quota = True
                    if resp.status_code in (400, 401, 402, 403, 404, 408, 429, 500, 502, 503, 504) and candidate != models_to_try[-1]:
                        logger.warning("Stream model %s failed HTTP %d, rotating...", candidate, resp.status_code)
                        continue
                    if looks_like_context_length_error(err_text):
                        raise EngineContextLengthError(f"Context length overflow: {err_text}")
                    if candidate != models_to_try[-1]:
                        continue
                    break

                stream_started = True
                async for line in resp.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data_str = line[len("data:") :].strip()
                    if not data_str or data_str == "[DONE]":
                        break
                    try:
                        chunk = json.loads(data_str)
                    except json.JSONDecodeError:
                        continue

                    if not isinstance(chunk, dict):
                        continue

                    choices = safe_get_list(chunk, "choices")
                    if not choices:
                        continue
                    choice = choices[0] if isinstance(choices[0], dict) else {}
                    delta = safe_get_dict(choice, "delta")
                    finish = choice.get("finish_reason")

                    content = delta.get("content")
                    if content is not None and str(content):
                        yield StreamChunk(content=str(content))

                    delta_tc = safe_get_list(delta, "tool_calls")
                    if delta_tc:
                        calls_to_yield = []
                        for tc in delta_tc:
                            if not isinstance(tc, dict):
                                continue
                            idx = tc.get("index", 0)
                            fn_dict = safe_get_dict(tc, "function")
                            if idx not in accumulated_tool_calls:
                                accumulated_tool_calls[idx] = {
                                    "index": idx,
                                    "id": str(tc.get("id") or ""),
                                    "type": "function",
                                    "function": {
                                        "name": str(fn_dict.get("name") or ""),
                                        "arguments": str(fn_dict.get("arguments") or ""),
                                    },
                                }
                            else:
                                curr = accumulated_tool_calls[idx]
                                if tc.get("id"):
                                    curr["id"] = str(tc["id"])
                                if fn_dict.get("name"):
                                    curr["function"]["name"] = str(fn_dict["name"])
                                if fn_dict.get("arguments"):
                                    curr["function"]["arguments"] += str(fn_dict["arguments"])
                            calls_to_yield.append(accumulated_tool_calls[idx])
                        yield StreamChunk(tool_calls=calls_to_yield)

                    if finish:
                        yield StreamChunk(finish_reason=str(finish))
            if stream_started:
                return
        except (httpx.ConnectError, httpx.TimeoutException, httpx.HTTPError) as exc:
            last_exc = exc
            if not stream_started and candidate != models_to_try[-1]:
                logger.warning("Stream error on model %s (%s), rotating...", candidate, exc)
                continue

    if not stream_started:
        is_fa = any("\u0600" <= c <= "\u06FF" for m in messages for c in (getattr(m, "content", "") or ""))
        if saw_quota:
            quota_reply = QUOTA_EXHAUSTED_REPLY_FA if is_fa else QUOTA_EXHAUSTED_REPLY_EN
            yield StreamChunk(content=quota_reply, finish_reason="quota_exhausted")
            return
        fallback_reply = (
            "ارتباط با سرور مدل هوش مصنوعی در حال حاضر مقدور نیست قربان؛ اما تمام امکانات آفلاین گوشی در دسترس است."
            if is_fa else
            "Unable to reach the AI model server, sir. All offline device controls remain fully operational."
        )
        yield StreamChunk(content=fallback_reply, finish_reason="error")
        return


VISION_MODEL_FALLBACK_POOL = [
    "google/gemma-3-27b-it:free",
    "qwen/qwen2.5-vl-72b-instruct:free",
    "meta-llama/llama-3.2-11b-vision-instruct:free",
    "mistralai/mistral-small-3.1-24b-instruct:free",
]


async def analyze_vision_multimodal(
    image_base64: str,
    prompt: str = "Describe what you see in detail and answer any visible questions.",
    api_key: Optional[str] = None,
    active_model: Optional[str] = None,
    gemini_key: Optional[str] = None,
) -> str:
    """Analyze an image using the dedicated multimodal vision pool with auto-fallback."""
    clean_b64 = image_base64.strip()
    if clean_b64.startswith("data:image"):
        clean_b64 = clean_b64.split(",", 1)[-1].strip()

    models_to_try: List[str] = []
    # 1. Current model ONLY if known multimodal
    if active_model:
        m_lower = active_model.lower()
        if any(k in m_lower for k in ("vision", "vl", "gemma-3", "gemini", "gpt-4o", "omni")):
            models_to_try.append(active_model)

    # 2-5. Dedicated multimodal vision fallback pool
    for m in VISION_MODEL_FALLBACK_POOL:
        if m not in models_to_try:
            models_to_try.append(m)

    headers = {
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/sajjadyarmahdi14-dev/open-jarvis",
        "X-Title": "Open Jarvis Vision",
    }
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    # Auto-fallback across vision candidates on 400, 404, 429, 5xx, or network timeout
    async with httpx.AsyncClient(timeout=45.0) as client:
        for candidate in models_to_try:
            actual_model = candidate.removeprefix("openrouter/")
            # Direct Gemini handling if gemini key is provided and candidate is gemini
            if "gemini" in candidate.lower() and gemini_key:
                try:
                    url = f"{GEMINI_API_BASE}/{actual_model}:generateContent?key={gemini_key}"
                    payload = {
                        "contents": [
                            {
                                "parts": [
                                    {"text": prompt},
                                    {
                                        "inline_data": {
                                            "mime_type": "image/jpeg",
                                            "data": clean_b64,
                                        }
                                    }
                                ]
                            }
                        ]
                    }
                    resp = await client.post(url, json=payload, headers={"Content-Type": "application/json"})
                    if resp.status_code == 200:
                        data = resp.json()
                        cands = data.get("candidates", [])
                        if cands:
                            parts = cands[0].get("content", {}).get("parts", [])
                            text = "".join(p.get("text", "") for p in parts).strip()
                            if text:
                                return text
                except Exception as ex:
                    logger.warning("Gemini vision candidate %s failed: %s", candidate, ex)

            # OpenRouter multimodal endpoint
            payload = {
                "model": actual_model,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {"type": "text", "text": prompt},
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{clean_b64}"
                                }
                            }
                        ]
                    }
                ],
                "max_tokens": 1500,
                "temperature": 0.3,
            }
            try:
                resp = await client.post(
                    f"{OPENROUTER_API_BASE}/chat/completions",
                    json=payload,
                    headers=headers,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    content = safe_extract_message_content(data).strip()
                    if content:
                        return content
                else:
                    logger.warning("Vision model %s failed with HTTP %d: %s", actual_model, resp.status_code, resp.text[:200])
            except Exception as ex:
                logger.warning("Vision request exception for %s: %s", actual_model, ex)

    return "I was unable to analyze the image through the visual models, sir."


async def analyze_vision_live_multimodal(
    current_frame_base64: str,
    recent_frames_base64: Optional[List[str]] = None,
    prompt: str = "Analyze what is on the screen and answer clearly.",
    ui_text_context: Optional[str] = None,
    foreground_app: Optional[str] = None,
    conversation_history: Optional[List[Dict[str, str]]] = None,
    api_key: Optional[str] = None,
    active_model: Optional[str] = None,
    gemini_key: Optional[str] = None,
) -> str:
    """Analyze a live screen frame with rolling context, UI text hierarchy, and multi-turn visual history."""
    clean_current_b64 = current_frame_base64.strip()
    if clean_current_b64.startswith("data:image"):
        clean_current_b64 = clean_current_b64.split(",", 1)[-1].strip()

    models_to_try: List[str] = []
    if active_model:
        m_lower = active_model.lower()
        if any(k in m_lower for k in ("vision", "vl", "gemma-3", "gemini", "gpt-4o", "omni")):
            models_to_try.append(active_model)

    for m in VISION_MODEL_FALLBACK_POOL:
        if m not in models_to_try:
            models_to_try.append(m)

    headers = {
        "Content-Type": "application/json",
        "HTTP-Referer": "https://github.com/sajjadyarmahdi14-dev/open-jarvis",
        "X-Title": "Open Jarvis Live Screen Vision",
    }
    if api_key:
        headers["Authorization"] = f"Bearer {api_key}"

    is_persian = any("\u0600" <= c <= "\u06FF" for c in prompt)

    system_instruction = (
        "You are J.A.R.V.I.S., an expert real-time AI assistant watching the user's smartphone screen live.\n"
        f"Active Foreground App: {foreground_app or 'Unknown'}\n"
        f"Extracted Screen UI Text & Hierarchy:\n{ui_text_context or 'None extracted'}\n\n"
        "Instructions:\n"
        "1. Answer the user's question with high accuracy, combining visible image pixels with the extracted UI text.\n"
        "2. Identify landmarks, places, famous people, actors, characters, products, movies, games, plants, and objects if asked.\n"
        "3. Read, translate, summarize, or simplify on-screen text, code, articles, or messages when requested.\n"
        "4. Solve math problems, quizzes, riddles, homework, or puzzles step-by-step with clear reasoning.\n"
        "5. Speak directly and politely (addressing user as sir/قربان in Persian, or sir in English).\n"
        "6. Keep the reply conversational and concise for Male Edge-TTS voice playback (avoid huge tables, ascii art, or robotic formatting)."
    )

    # Build multi-turn messages
    messages: List[Dict[str, Any]] = [
        {"role": "system", "content": system_instruction}
    ]

    if conversation_history:
        for turn in conversation_history[-4:]:
            role = turn.get("role", "user")
            content = turn.get("content", "")
            if content:
                messages.append({"role": role, "content": content})

    # Latest user message with image(s) and prompt
    user_content: List[Dict[str, Any]] = [
        {"type": "text", "text": prompt}
    ]

    # Include up to 1 previous keyframe if distinct
    if recent_frames_base64:
        for prev_b64 in recent_frames_base64[-1:]:
            c_prev = prev_b64.strip()
            if c_prev.startswith("data:image"):
                c_prev = c_prev.split(",", 1)[-1].strip()
            if c_prev and c_prev != clean_current_b64:
                user_content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/jpeg;base64,{c_prev}"}
                })

    user_content.append({
        "type": "image_url",
        "image_url": {"url": f"data:image/jpeg;base64,{clean_current_b64}"}
    })

    messages.append({"role": "user", "content": user_content})

    async with httpx.AsyncClient(timeout=45.0) as client:
        for candidate in models_to_try:
            actual_model = candidate.removeprefix("openrouter/")

            # Gemini direct support
            if "gemini" in candidate.lower() and gemini_key:
                try:
                    url = f"{GEMINI_API_BASE}/{actual_model}:generateContent?key={gemini_key}"
                    parts: List[Dict[str, Any]] = [
                        {"text": f"{system_instruction}\n\nQuestion: {prompt}"},
                        {
                            "inline_data": {
                                "mime_type": "image/jpeg",
                                "data": clean_current_b64,
                            }
                        }
                    ]
                    resp = await client.post(url, json={"contents": [{"parts": parts}]}, headers={"Content-Type": "application/json"})
                    if resp.status_code == 200:
                        data = resp.json()
                        cands = data.get("candidates", [])
                        if cands:
                            content_parts = cands[0].get("content", {}).get("parts", [])
                            text = "".join(p.get("text", "") for p in content_parts).strip()
                            if text:
                                return text
                except Exception as ex:
                    logger.debug("Gemini live vision candidate %s failed: %s", candidate, ex)

            # OpenRouter support
            payload = {
                "model": actual_model,
                "messages": messages,
                "max_tokens": 1500,
                "temperature": 0.25,
            }
            try:
                resp = await client.post(
                    f"{OPENROUTER_API_BASE}/chat/completions",
                    json=payload,
                    headers=headers,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    content = safe_extract_message_content(data).strip()
                    if content:
                        return content
                else:
                    logger.warning("Live vision model %s returned HTTP %d: %s", actual_model, resp.status_code, resp.text[:200])
            except Exception as ex:
                logger.warning("Live vision candidate %s exception: %s", actual_model, ex)

    if is_persian:
        return "قربان، متأسفانه در حال حاضر تحلیل محتوای صفحه با مدل‌های بینایی مقدور نبود."
    return "I was unable to analyze the screen content at this moment, sir."


__all__ = [
    "FREE_MODEL_FALLBACK_POOL",
    "QUOTA_EXHAUSTED_REPLY_EN",
    "QUOTA_EXHAUSTED_REPLY_FA",
    "VISION_MODEL_FALLBACK_POOL",
    "analyze_vision_live_multimodal",
    "analyze_vision_multimodal",
    "build_gemini_contents",
    "build_gemini_payload",
    "convert_tools_to_gemini",
    "generate_gemini_rest",
    "generate_openai_compat_rest",
    "is_quota_status_or_text",
    "normalize_openai_tools",
    "safe_extract_message_content",
    "safe_extract_tool_calls",
    "safe_get_dict",
    "safe_get_list",
    "stream_full_gemini_rest",
    "stream_full_openai_compat_rest",
]
