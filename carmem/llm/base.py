from __future__ import annotations

import json
from typing import Any, Protocol


class LLMClient(Protocol):
    """Minimal contract: return JSON that matches ``schema`` for a system+user prompt."""

    def complete_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]: ...


class NullLLM:
    """Always raises, so callers fall back to deterministic rules. Useful offline and in tests."""

    def complete_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        raise RuntimeError("no LLM configured")


class ScriptedLLM:
    """Returns canned JSON by substring match. For tests and demos."""

    def __init__(self, responses: dict[str, dict[str, Any]]):
        self.responses = responses

    def complete_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        for needle, resp in self.responses.items():
            if needle.lower() in user.lower():
                return resp
        return {"candidates": []}


def parse_json_text(text: str) -> dict[str, Any]:
    text = text.strip()
    if text.startswith("```"):
        text = text.strip("`")
        if text.lower().startswith("json"):
            text = text[4:]
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end == -1:
        raise ValueError("no JSON object in response")
    return json.loads(text[start : end + 1])
