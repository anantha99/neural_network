"""Tool definitions so an assistant can call memory as tools.

Neutral shape here; converters for the Anthropic and OpenAI wire formats below.
Dispatch is :meth:`carmem.memory.CarMemory.handle_tool`.
"""

from __future__ import annotations

from typing import Any

MEMORY_TOOLS: list[dict[str, Any]] = [
    {
        "name": "memory_recall",
        "description": "Search the driver's memory (facts, routines, recent trips, vehicle notes) for the current "
                       "occupant only. Use before answering anything that depends on the driver's history.",
        "parameters": {
            "type": "object",
            "properties": {"query": {"type": "string"}, "k": {"type": "integer", "default": 6}},
            "required": ["query"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory_remember",
        "description": "Store something the driver just said that should persist across trips.",
        "parameters": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "The utterance, verbatim."}},
            "required": ["text"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory_forget",
        "description": "Delete a memory the driver wants gone. Pass the record id, or a short description to search.",
        "parameters": {
            "type": "object",
            "properties": {"target": {"type": "string"}},
            "required": ["target"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory_correct",
        "description": "Driver answered a proactive suggestion. answer='no' demotes it, 'yes' confirms it.",
        "parameters": {
            "type": "object",
            "properties": {"answer": {"type": "string", "enum": ["yes", "no"]}},
            "required": ["answer"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory_explain",
        "description": "Show the evidence behind a memory record so the driver can audit it.",
        "parameters": {
            "type": "object",
            "properties": {"record_id": {"type": "string"}},
            "required": ["record_id"],
            "additionalProperties": False,
        },
    },
    {
        "name": "memory_proactive",
        "description": "What the car would do right now on its own, with the tier that governs each item.",
        "parameters": {
            "type": "object",
            "properties": {"location": {"type": "string", "description": "Where the car is now, if known."}},
            "additionalProperties": False,
        },
    },
]


def to_anthropic_tools(tools: list[dict[str, Any]] = MEMORY_TOOLS) -> list[dict[str, Any]]:
    return [
        {"name": t["name"], "description": t["description"], "input_schema": t["parameters"], "strict": True}
        for t in tools
    ]


def to_openai_tools(tools: list[dict[str, Any]] = MEMORY_TOOLS) -> list[dict[str, Any]]:
    return [
        {"type": "function", "function": {"name": t["name"], "description": t["description"],
                                          "parameters": t["parameters"], "strict": True}}
        for t in tools
    ]
