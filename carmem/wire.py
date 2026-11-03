"""The wire schema: JSON shapes shared by every implementation and client.

These are the contract between the Python reference, the Kotlin head-unit
service, the on-device assistant and the cloud bridge. The conformance
fixtures in ``fixtures/`` use exactly these shapes.
"""

from __future__ import annotations

import json
from typing import Any

from .llm.tools import MEMORY_TOOLS

_TS = {"type": "string", "description": "ISO-8601 local time without offset, e.g. 2026-09-07T07:40:00"}

IDENTITY_SIGNAL = {
    "type": "object",
    "properties": {
        "kind": {"type": "string", "enum": ["seat_profile", "phone", "key_fob", "voice"]},
        "value": {"type": "string"},
        "confidence": {"type": "number", "default": 1.0},
        "role": {"type": "string", "enum": ["driver", "passenger"], "default": "driver"},
    },
    "required": ["kind", "value"],
    "additionalProperties": False,
}

TRIP = {
    "type": "object",
    "properties": {
        "start": _TS, "end": _TS, "origin": {"type": "string"}, "destination": {"type": "string"},
        "occupants": {"type": "array", "items": {"type": "string"}},
        "settings": {"type": "object", "additionalProperties": {"type": ["number", "string", "boolean"]}},
        "route": {"type": "object", "additionalProperties": True},
        "distance_km": {"type": "number"},
    },
    "required": ["start", "end", "origin", "destination"],
    "additionalProperties": False,
}

EVENT = {
    "type": "object",
    "description": "One line of a driver log. 'driver' is a person id or 'guest' or 'vehicle' for sensors.",
    "properties": {
        "ts": _TS,
        "type": {"type": "string", "enum": ["trip", "say", "sensor"]},
        "driver": {"type": "string"},
        "week": {"type": "integer"},
        "trip": TRIP,
        "text": {"type": "string"},
        "payload": {"type": "object", "additionalProperties": True},
        "reject_if_proposed": {"type": "array", "items": {"type": "string"}},
        "forget_if_proposed": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["ts", "type", "driver"],
    "additionalProperties": False,
}

PROPOSAL = {
    "type": "object",
    "properties": {
        "id": {"type": "string"},
        "kind": {"type": "string", "enum": ["routine", "fact"]},
        "record_id": {"type": "string"},
        "scope": {"type": "string"},
        "tier": {"type": "string", "enum": ["never", "silent", "suggest", "act"]},
        "text": {"type": "string"},
        "action": {"type": "object", "additionalProperties": True},
    },
    "required": ["kind", "record_id", "scope", "tier", "text", "action"],
    "additionalProperties": False,
}

CONTEXT_PACKET = {
    "type": "object",
    "properties": {
        "driver_id": {"type": "string"},
        "destination": {"type": "string", "enum": ["local", "cloud"]},
        "facts": {"type": "array", "items": {"type": "object", "properties": {
            "id": {"type": "string"}, "text": {"type": "string"}, "source": {"type": "string"}, "state": {"type": "string"}},
            "required": ["id", "text", "source", "state"]}},
        "routines": {"type": "array", "items": {"type": "object", "additionalProperties": True}},
        "vehicle": {"type": "array", "items": {"type": "object", "additionalProperties": True}},
        "episodes": {"type": "array", "items": {"type": "object", "additionalProperties": True}},
        "masked_count": {"type": "integer"},
    },
    "required": ["driver_id", "destination", "facts", "routines", "vehicle", "episodes", "masked_count"],
    "additionalProperties": False,
}

WIRE_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "title": "carmem wire schema",
    "version": "1",
    "$defs": {
        "IdentitySignal": IDENTITY_SIGNAL,
        "Trip": TRIP,
        "Event": EVENT,
        "Proposal": PROPOSAL,
        "ContextPacket": CONTEXT_PACKET,
    },
    "tools": MEMORY_TOOLS,
    "replay_protocol": [
        "Events are sorted by ts. A sensor event calls record_vehicle_event(key, value, now=ts) with no session.",
        "When (driver, date) changes: ignition_off(now = ts - 1 min) if a session is open, then "
        "ignition_on(signals for driver, now = ts - 5 min). 'guest' uses one voice signal with confidence 0.55.",
        "A 'say' event calls hear(text, now=ts).",
        "A 'trip' event first calls proactive(now = start - 2 min, location = origin). Each routine proposal is logged "
        "as (ts=start, scope, destination, tier, correct = destination == trip.destination).",
        "If a routine proposal's destination matches reject_if_proposed: hear('no', now = start - 1 min).",
        "If a routine proposal's destination contains a forget_if_proposed entry: hear('forget that', now = start - 1 min). "
        "If none matched: hear('forget that I go to the <entry>', now = start - 1 min).",
        "Then record_trip(trip, now = end).",
        "After the last event: ignition_off(now = last ts + 1 h).",
    ],
}


def dump_wire_schema() -> str:
    return json.dumps(WIRE_SCHEMA, indent=2)
