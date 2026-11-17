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
    max_tokens: int = 1024,
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
    max_tokens: int = 1024,
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


def generate_openai_compat_rest(
    client: httpx.Client,
    base_url: str,
    api_key: Optional[str],
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 1024,
    extra_headers: Optional[Dict[str, str]] = None,
    **kwargs: Any,
) -> Dict[str, Any]:
    """Synchronous OpenAI-compatible completion with tool_calls support."""
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
    # Optimize TTFT (time-to-first-token) and eliminate hidden reasoning delay on OpenRouter
    model_lower = model.lower()
    if "openrouter" in base_url.lower() or any(k in model_lower for k in ("reasoning", "nemotron", "deepseek", "qwen")):
        if "reasoning" not in payload:
            payload["reasoning"] = {"effort": "low", "exclude": True}
        if payload.get("max_tokens", 1024) > 350:
            payload["max_tokens"] = 350
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
    try:
        resp = client.post(url, json=payload, headers=headers)
        if resp.status_code == 429 and "openrouter" in base_url.lower() and payload.get("model") != "openrouter/free":
            logger.info("OpenRouter model %s returned 429, instantly falling back to openrouter/free", payload.get("model"))
            fallback_payload = dict(payload)
            fallback_payload["model"] = "openrouter/free"
            resp = client.post(url, json=fallback_payload, headers=headers)
        if resp.status_code == 400 and "tools" in payload:
            payload.pop("tools", None)
            payload.pop("tool_choice", None)
            resp = client.post(url, json=payload, headers=headers)
        resp.raise_for_status()
    except httpx.HTTPStatusError as exc:
        err_msg = exc.response.text
        if looks_like_context_length_error(err_msg):
            raise EngineContextLengthError(f"Context length overflow: {err_msg}") from exc
        raise EngineConnectionError(f"API returned HTTP {exc.response.status_code}: {err_msg}") from exc
    except (httpx.ConnectError, httpx.TimeoutException) as exc:
        raise EngineConnectionError(f"Connection failed to {url}") from exc

    elapsed = time.monotonic() - t0
    data = resp.json()
    choices = data.get("choices", [])
    if not choices:
        return {
            "content": "",
            "usage": data.get("usage", {}),
            "model": data.get("model", model),
            "finish_reason": "error",
            "cost_usd": 0.0,
            "ttft": elapsed,
        }

    choice = choices[0]
    usage = data.get("usage", {})
    prompt_tokens = usage.get("prompt_tokens") or estimate_prompt_tokens(messages)
    completion_tokens = usage.get("completion_tokens", 0)

    result: Dict[str, Any] = {
        "content": choice.get("message", {}).get("content") or "",
        "usage": {
            "prompt_tokens": prompt_tokens,
            "completion_tokens": completion_tokens,
            "total_tokens": prompt_tokens + completion_tokens,
        },
        "model": data.get("model", model),
        "finish_reason": choice.get("finish_reason", "stop"),
        "cost_usd": 0.0,
        "ttft": elapsed,
    }

    raw_tool_calls = choice.get("message", {}).get("tool_calls", [])
    if raw_tool_calls:
        result["tool_calls"] = [
            {
                "id": tc.get("id", ""),
                "name": tc.get("function", {}).get("name", ""),
                "arguments": tc.get("function", {}).get("arguments", "{}"),
            }
            for tc in raw_tool_calls
        ]
    return result


async def stream_full_openai_compat_rest(
    async_client: httpx.AsyncClient,
    base_url: str,
    api_key: Optional[str],
    model: str,
    messages: Sequence[Message],
    temperature: float = 0.7,
    max_tokens: int = 1024,
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
    # Optimize TTFT (time-to-first-token) and eliminate hidden reasoning delay on OpenRouter
    model_lower = model.lower()
    if "openrouter" in base_url.lower() or any(k in model_lower for k in ("reasoning", "nemotron", "deepseek", "qwen")):
        if "reasoning" not in payload:
            payload["reasoning"] = {"effort": "low", "exclude": True}
        if payload.get("max_tokens", 1024) > 350:
            payload["max_tokens"] = 350
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

    try:
        async with async_client.stream("POST", url, json=payload, headers=headers) as resp:
            if not resp.is_success:
                await resp.aread()
                err_text = resp.text
                if looks_like_context_length_error(err_text):
                    raise EngineContextLengthError(f"Context length overflow: {err_text}")
                raise EngineConnectionError(f"API returned HTTP {resp.status_code}: {err_text}")

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

                choices = chunk.get("choices", [])
                if not choices:
                    continue
                choice = choices[0]
                delta = choice.get("delta", {})
                finish = choice.get("finish_reason")

                content = delta.get("content")
                if content:
                    yield StreamChunk(content=content)

                delta_tc = delta.get("tool_calls")
                if delta_tc:
                    calls_to_yield = []
                    for tc in delta_tc:
                        idx = tc.get("index", 0)
                        if idx not in accumulated_tool_calls:
                            accumulated_tool_calls[idx] = {
                                "index": idx,
                                "id": tc.get("id", ""),
                                "type": "function",
                                "function": {
                                    "name": tc.get("function", {}).get("name", ""),
                                    "arguments": tc.get("function", {}).get("arguments", ""),
                                },
                            }
                        else:
                            curr = accumulated_tool_calls[idx]
                            if tc.get("id"):
                                curr["id"] = tc["id"]
                            if tc.get("function", {}).get("name"):
                                curr["function"]["name"] = tc["function"]["name"]
                            if tc.get("function", {}).get("arguments"):
                                curr["function"]["arguments"] += tc["function"]["arguments"]
                        calls_to_yield.append(accumulated_tool_calls[idx])
                    yield StreamChunk(tool_calls=calls_to_yield)

                if finish:
                    yield StreamChunk(finish_reason=finish)

    except (httpx.ConnectError, httpx.TimeoutException) as exc:
        raise EngineConnectionError(f"Stream connection failed to {url}") from exc


__all__ = [
    "build_gemini_contents",
    "build_gemini_payload",
    "convert_tools_to_gemini",
    "generate_gemini_rest",
    "generate_openai_compat_rest",
    "normalize_openai_tools",
    "stream_full_gemini_rest",
    "stream_full_openai_compat_rest",
]
