"""Adapter for any server speaking the OpenAI chat-completions wire format
(local llama.cpp / vLLM / Ollama endpoints included). Standard library only."""

from __future__ import annotations

import json
import os
import urllib.request
from typing import Any

from .base import parse_json_text


class OpenAICompatibleLLM:
    def __init__(self, model: str, base_url: str = "http://localhost:11434/v1", api_key: str | None = None,
                 timeout: float = 20.0):
        self.model = model
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key or os.environ.get("OPENAI_API_KEY", "")
        self.timeout = timeout

    def complete_json(self, system: str, user: str, schema: dict[str, Any]) -> dict[str, Any]:
        body = {
            "model": self.model,
            "messages": [
                {"role": "system", "content": system + "\nRespond with a single JSON object and nothing else."},
                {"role": "user", "content": user},
            ],
            "response_format": {"type": "json_schema", "json_schema": {"name": "memory", "schema": schema}},
        }
        req = urllib.request.Request(
            f"{self.base_url}/chat/completions",
            data=json.dumps(body).encode(),
            headers={"Content-Type": "application/json", "Authorization": f"Bearer {self.api_key}"},
        )
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            data = json.loads(resp.read().decode())
        return parse_json_text(data["choices"][0]["message"]["content"])
