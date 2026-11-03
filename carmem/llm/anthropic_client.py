"""Claude adapter via the official ``anthropic`` SDK (``pip install carmem[anthropic]``).

Extraction is a small, bounded job, so it runs at low effort. Structured
output guarantees the reply is valid JSON for the schema we pass.
"""

from __future__ import annotations

import os
from typing import Any

from .base import parse_json_text


class AnthropicLLM:
    def __init__(self, model: str = "claude-opus-5-5", api_key: str | None = None, effort: str = "low",
                 max_tokens: int = 1024):
        import anthropic  # imported lazily so the core stays dependency-free

        self._anthropic = anthropic
        self.client = anthropic.Anthropic(api_key=api_key or os.environ.get("ANTHROPIC_API_KEY"))
        self.model = model
        self.effort = effort
        self.max_tokens = max_tokens

    def complete_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        response = self.client.beta.messages.create(
            model=self.model,
            max_tokens=self.max_tokens,
            system=system,
            messages=[{"role": "user", "content": user}],
            output_config={"effort": self.effort, "format": {"type": "json_schema", "schema": schema}},
            betas=["server-side-fallback-2026-07-01"],
            fallbacks="default",
        )
        if response.stop_reason == "refusal":
            raise RuntimeError("extraction request was declined")
        text = next(b.text for b in response.content if b.type == "text")
        return parse_json_text(text)
