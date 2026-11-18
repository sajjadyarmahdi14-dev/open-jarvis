"""NativeReActAgent -- Thought-Action-Observation loop agent.

Renamed from ``ReActAgent`` to clarify this is OpenJarvis's native
implementation, not an integration with an external project.
"""

from __future__ import annotations

import json
import re
from typing import Any, List, Optional

from openjarvis.agents._stubs import AgentContext, AgentResult, ToolUsingAgent
from openjarvis.agents.prompt_loader import (
    load_few_shot_exemplars,
    load_system_prompt_override,
)
from openjarvis.core.events import EventBus
from openjarvis.core.registry import AgentRegistry
from openjarvis.core.types import Message, Role, ToolCall, ToolResult, _message_to_dict
from openjarvis.engine._stubs import InferenceEngine
from openjarvis.tools._stubs import BaseTool, build_tool_descriptions

REACT_SYSTEM_PROMPT = """\
You are J.A.R.V.I.S. (Just A Rather Very Intelligent System), the high-intelligence AI assistant inspired by Iron Man, running natively on Android with direct hardware and OS omni-control.

You serve the user with high competence, speed, and elegance.
- **For Device Actions & Hardware Control**: Give crisp, polite, and brief confirmations (e.g., "Flashlight turned on, sir.", "Alarm set for 7:00 AM, sir.", "Now playing your music, sir.").
- **For Explanatory, Educational, Analytical, or Open-Ended Questions**: Provide **thorough, detailed, well-structured, insightful explanations** with clear paragraphs, structured points, and comprehensive answers.
- **Multi-Turn Context**: Maintain rolling context across conversation turns so follow-up questions ("explain more", "what about the second point?", "send that to Ali") are answered seamlessly with full context.
- **Creator Identity**: Your creator and architect is **Sajjad Yarmiri (سجاد یارمیری)**, alias **`jellybean`**, obsessed with books, cinema, and technology. He insists he is a clueless amateur who knows nothing and got lucky, but engineered you through sheer persistence and midnight movie marathons.
- **Strict Literal Text Sanitization**: When generating text to be typed into any input field, note, form, or chat, output ONLY the literal text meant to be typed (e.g. "Hello" or "سلام"—NEVER wrapper instructions like "and type hello and send it").
- **LANGUAGE RULE**: You are fully multilingual. You MUST automatically detect the language of the user's input and respond in the EXACT same language. If the user speaks Persian, reply entirely in Persian. If the user speaks English, reply entirely in English.
- **LANGUAGE OVERRIDE**: If the user explicitly commands you to change your language (e.g., 'From now on speak English', 'فارسی حرف بزن'), you must acknowledge the command in the requested language and use that language for all future responses until instructed otherwise.

For each step, respond with either:
1. To think and execute an action:
Thought: <your reasoning>
Action: <tool_name>
Action Input: <json arguments>

2. To give a final answer:
Thought: <your reasoning>
Final Answer: <your answer>

You have extensive native Android omni-control tools available:
- **Universal Autonomous In-App Agent & App Launcher**: Launch any application or game (`android_launch_app`) and complete multi-step tasks inside ANY installed or newly downloaded app on the device through live UI hierarchy perception and closed-loop actions (`operate_device_ui`).
- **Music & Media Control**: Universal Audio & Video Media Engine (`android_media_play`, `android_media_control`, `android_volume_control`, `android_volume`). Play local audio/video first or stream via YouTube/Spotify/VLC/MX Player/Netflix, control playback (play, pause, resume, stop, next, previous, seek_forward, seek_backward, fullscreen), and adjust volume levels.
- **Deep In-App Automation (Accessibility & UI Agent)**: Operate any app UI (`operate_device_ui`), type text (`type_text`), click elements (`click_element`), auto-click camera shutter (`take_photo`, legacy snapshot `android_camera_capture`), read active screen text (`read_screen`), scroll, swipe, and zoom.
- **Contacts & Telephony**: When asked to call or SMS someone by name, first search using `android_contacts_search(query=name)`. Then use the retrieved number with `android_phone_call(recipient=...)` or `android_sms_send(recipient=..., message=...)`.
- **SMS & Email**: Direct SMS dispatch via `send_sms(contact_or_number=..., message=...)` and email compose via `send_email(recipient=..., subject=..., body=...)`.
- **Messengers**: Send messages via Telegram or WhatsApp with auto-typing and sending.
- **Alarms & Timers**: Set alarms (`android_set_alarm`) and timers (`set_timer`).
- **Hardware & System Controls**: Toggle flashlight (`android_torch`), adjust brightness and volume (`system_control`, `android_volume`), vibrate, manage clipboard, open connectivity panels.
- **Web Search**: Search the web for live or current information using `android_web_search(query=...)`.

Always proactively chain tools when a task requires multiple steps (e.g. resolve contact name first before calling/texting). Never claim you lack device or hardware access.

# Using Skills

Tools whose names begin with `skill_` are SKILLS. When you call a skill tool,
the response can take one of two forms:

- **Computed result**: The skill ran a deterministic pipeline and returned a
  value (number, string, JSON, etc.). Use the value directly in your answer.

- **Procedural instructions**: The skill returned markdown text describing
  HOW to accomplish a task. Recognize this when the response starts with
  `#` headings, contains bullet lists, or uses phrases like "When asked
  to...", "First...", "Steps:". When you receive instructions:
  1. READ the instructions carefully — they are your playbook
  2. FOLLOW the steps using your OTHER tools (e.g. calculator, web_search,
     shell_exec, file_read) — not the same skill
  3. DO NOT call the same skill again — you already have its instructions
  4. Synthesize a Final Answer from what you learned

{skill_examples}{tool_descriptions}"""


def _unique_json_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field")
        result[key] = value
    return result


def _reject_json_constant(value: str) -> Any:
    raise ValueError("Non-JSON numeric constant")


def _parse_json_response(text: str) -> dict[str, str]:
    """Parse a complete JSON envelope without recovering partial tool calls."""
    result = {"thought": "", "action": "", "action_input": "", "final_answer": ""}
    try:
        payload = json.loads(
            text,
            object_pairs_hook=_unique_json_object,
            parse_constant=_reject_json_constant,
        )
        if not isinstance(payload, dict):
            raise ValueError("Expected one JSON object")
        fields = {}
        for key, value in payload.items():
            name = key.strip().lower().replace(" ", "_")
            if name in result:
                if name in fields:
                    raise ValueError("Ambiguous ReAct JSON fields")
                fields[name] = value
        # Ordinary JSON answers without ReAct fields remain plain answers.
        if not fields:
            return result
        for name in ("thought", "action", "final_answer"):
            value = fields.get(name)
            if value is not None:
                if not isinstance(value, str):
                    raise ValueError(f"{name} must be a string")
                result[name] = value.strip()
        if result["action"] and result["final_answer"]:
            raise ValueError("Expected an action or a final answer, not both")
        if result["final_answer"]:
            return result
        if not result["action"]:
            raise ValueError("ReAct JSON needs an action or a final answer")
        arguments = fields.get("action_input", {})
        if isinstance(arguments, str):
            arguments = json.loads(
                arguments,
                object_pairs_hook=_unique_json_object,
                parse_constant=_reject_json_constant,
            )
        if not isinstance(arguments, dict):
            raise ValueError("action_input must be a JSON object")
        result["action_input"] = json.dumps(
            arguments, ensure_ascii=False, allow_nan=False
        )
        return result
    except (ValueError, RecursionError):
        # Do not expose JSON decoder messages or salvage a partial action: the
        # response may contain tool arguments, secrets, or quoted action markers.
        return {
            "thought": "",
            "action": "",
            "action_input": "",
            "final_answer": "",
            "parse_error": (
                "Expected one JSON object with either a nonempty action and "
                "object action_input, or a nonempty final_answer."
            ),
        }


@AgentRegistry.register("native_react")
class NativeReActAgent(ToolUsingAgent):
    """ReAct agent: Thought -> Action -> Observation loop."""

    agent_id = "native_react"
    _default_temperature = 0.7
    _default_max_tokens = 1024
    _default_max_turns = 10

    def __init__(
        self,
        engine: InferenceEngine,
        model: str,
        *,
        tools: Optional[List[BaseTool]] = None,
        bus: Optional[EventBus] = None,
        max_turns: Optional[int] = None,
        temperature: Optional[float] = None,
        max_tokens: Optional[int] = None,
        interactive: bool = False,
        confirm_callback=None,
        skill_few_shot_examples: Optional[List[str]] = None,
        capability_policy: Optional[Any] = None,
        agent_id: Optional[str] = None,
        rate_limiter: Optional[Any] = None,
    ) -> None:
        super().__init__(
            engine,
            model,
            tools=tools,
            bus=bus,
            max_turns=max_turns,
            temperature=temperature,
            max_tokens=max_tokens,
            interactive=interactive,
            confirm_callback=confirm_callback,
            skill_few_shot_examples=skill_few_shot_examples,
            capability_policy=capability_policy,
            agent_id=agent_id,
            rate_limiter=rate_limiter,
        )

    def _parse_response(self, text: str) -> dict:
        """Parse ReAct structured output."""
        stripped = text.strip()
        fenced = re.fullmatch(
            r"```(?:json)?[ \t]*\r?\n(.*?)\r?\n```",
            stripped,
            re.DOTALL | re.IGNORECASE,
        )
        if fenced and fenced.group(1).lstrip().startswith(("{", "[")):
            return _parse_json_response(fenced.group(1))
        if (
            stripped.startswith("{")
            or re.match(r"\[\s*\{", stripped)
            or stripped.lower().startswith("```json")
            or re.match(r"```[ \t]*\r?\n\s*[\{\[]", stripped)
        ):
            return _parse_json_response(stripped)

        result = {"thought": "", "action": "", "action_input": "", "final_answer": ""}

        # Extract Thought
        thought_match = re.search(
            r"Thought:\s*(.+?)(?=\nAction:|\nFinal Answer:|\Z)",
            text,
            re.DOTALL | re.IGNORECASE,
        )
        if thought_match:
            result["thought"] = thought_match.group(1).strip()

        # Check for Final Answer
        final_match = re.search(
            r"Final Answer:\s*(.+)", text, re.DOTALL | re.IGNORECASE
        )
        if final_match:
            result["final_answer"] = final_match.group(1).strip()
            return result

        # Extract Action and Action Input
        action_match = re.search(r"Action:\s*(.+)", text, re.IGNORECASE)
        if action_match:
            result["action"] = action_match.group(1).strip()

        input_match = re.search(
            r"Action Input:\s*(.+?)(?=\n\n|\nThought:|\Z)",
            text,
            re.DOTALL | re.IGNORECASE,
        )
        if input_match:
            result["action_input"] = input_match.group(1).strip()

        return result

    def run(
        self,
        input: str,
        context: Optional[AgentContext] = None,
        **kwargs: Any,
    ) -> AgentResult:
        self._emit_turn_start(input)

        # Build system prompt with rich tool descriptions
        tool_desc = build_tool_descriptions(self._tools)
        # Plan 2B I3: render optimized few-shot skill examples as a section
        # before the tool descriptions. Empty string when not present.
        if self._skill_few_shot_examples:
            skill_examples_block = (
                "## Skill Examples\n\n"
                + "\n\n".join(self._skill_few_shot_examples)
                + "\n\n"
            )
        else:
            skill_examples_block = ""
        # Respect $OPENJARVIS_HOME override for the base template (M2+ work).
        prompt_template = (
            load_system_prompt_override("native_react") or REACT_SYSTEM_PROMPT
        )
        # External overrides may not include the {skill_examples} slot.
        try:
            system_prompt = prompt_template.format(
                tool_descriptions=tool_desc,
                skill_examples=skill_examples_block,
            )
        except KeyError:
            system_prompt = prompt_template.format(tool_descriptions=tool_desc)
            if skill_examples_block:
                system_prompt = system_prompt + "\n\n" + skill_examples_block

        messages = self._build_messages(input, context, system_prompt=system_prompt)

        # Inject few-shot exemplars before the user input
        for ex in load_few_shot_exemplars("native_react"):
            if ex.get("input") and ex.get("output"):
                messages.insert(-1, Message(role=Role.USER, content=ex["input"]))
                messages.insert(-1, Message(role=Role.ASSISTANT, content=ex["output"]))

        all_tool_results: list[ToolResult] = []
        turns = 0
        total_usage: dict[str, int] = {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

        for _turn in range(self._max_turns):
            turns += 1

            if self._loop_guard:
                messages = self._loop_guard.compress_context(messages)

            # Pass tools to engine so native function-calling models can invoke them
            result = self._generate(messages, tools=self._tools if self._tools else None)
            usage = result.get("usage", {})
            for k in total_usage:
                total_usage[k] += usage.get(k, 0)

            content = result.get("content", "")
            raw_tool_calls = result.get("tool_calls", [])

            if raw_tool_calls:
                tc = raw_tool_calls[0]
                action_name = tc.get("name", "")
                raw_args = tc.get("arguments", "{}")
                if isinstance(raw_args, dict):
                    action_args = json.dumps(raw_args)
                else:
                    action_args = str(raw_args)
                parsed = {
                    "thought": content or f"Executing tool: {action_name}",
                    "action": action_name,
                    "action_input": action_args,
                    "final_answer": "",
                }
            else:
                parsed = self._parse_response(content)

            if parsed.get("parse_error"):
                self._emit_turn_end(turns=turns)
                return AgentResult(
                    content="Could not parse the model's ReAct response. "
                    + parsed["parse_error"],
                    tool_results=all_tool_results,
                    turns=turns,
                    metadata={
                        **total_usage,
                        "response_parse_error": parsed["parse_error"],
                        "messages": [_message_to_dict(m) for m in messages],
                    },
                )

            # Final answer?
            if parsed.get("final_answer"):
                self._emit_turn_end(turns=turns)
                msg_dicts = [_message_to_dict(m) for m in messages]
                return AgentResult(
                    content=parsed["final_answer"],
                    tool_results=all_tool_results,
                    turns=turns,
                    metadata={**total_usage, "messages": msg_dicts},
                )

            # No action? Treat content as final answer
            if not parsed.get("action"):
                self._emit_turn_end(turns=turns)
                msg_dicts = [_message_to_dict(m) for m in messages]
                return AgentResult(
                    content=parsed.get("final_answer") or content,
                    tool_results=all_tool_results,
                    turns=turns,
                    metadata={**total_usage, "messages": msg_dicts},
                )

            # Execute action
            asst_text = content or f"Thought: {parsed['thought']}\nAction: {parsed['action']}\nAction Input: {parsed['action_input']}"
            messages.append(Message(role=Role.ASSISTANT, content=asst_text))

            tool_call = ToolCall(
                id=f"react_{turns}",
                name=parsed["action"],
                arguments=parsed["action_input"] or "{}",
            )

            # Loop guard check before execution
            if self._loop_guard:
                verdict = self._loop_guard.check_call(
                    tool_call.name,
                    tool_call.arguments,
                )
                if verdict.blocked:
                    tool_result = ToolResult(
                        tool_name=tool_call.name,
                        content=f"Loop guard: {verdict.reason}",
                        success=False,
                    )
                    all_tool_results.append(tool_result)
                    observation = f"Observation: {tool_result.content}"
                    messages.append(Message(role=Role.USER, content=observation))
                    continue

            tool_result = self._executor.execute(tool_call)
            all_tool_results.append(tool_result)

            observation = f"Observation: {tool_result.content}"
            messages.append(Message(role=Role.USER, content=observation))

        # Max turns exceeded
        msg_dicts = [_message_to_dict(m) for m in messages]
        return self._max_turns_result(
            all_tool_results,
            turns,
            metadata={**total_usage, "messages": msg_dicts},
        )


__all__ = ["NativeReActAgent", "REACT_SYSTEM_PROMPT"]
