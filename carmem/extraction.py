"""Turn utterances into typed memory candidates and intents.

Two extractors share one output type. :class:`RuleExtractor` is deterministic
and offline. :class:`LLMExtractor` asks any LLM client for the same JSON shape
and falls back to the rules when the model is unavailable or returns junk.
Driving-time intents (``no``, ``yes``, ``forget that``, ``why``) are always
rule-based: they must work with zero latency and no network.
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Any, Optional

from .llm.base import LLMClient


@dataclass
class Candidate:
    category: str
    key: str
    value: Any
    condition: Optional[str] = None
    kind: str = "stated"  # stated | observed (a command we saw, not a preference we were told)
    about: str = "person"  # person | vehicle

    def to_dict(self) -> dict[str, Any]:
        return {
            "category": self.category, "key": self.key, "value": self.value,
            "condition": self.condition, "kind": self.kind, "about": self.about,
        }


@dataclass
class Intent:
    name: str  # none | reject | accept | forget | explain
    target: Optional[str] = None  # free text target for forget
    raw: str = ""


@dataclass
class Extraction:
    intent: Intent
    candidates: list[Candidate] = field(default_factory=list)


_REJECT = re.compile(r"^\s*(no|nope|nah|stop|don'?t|not now|wrong|cancel)\b", re.I)
_ACCEPT = re.compile(r"^\s*(yes|yeah|yep|ok(ay)?|sure|please do|go ahead|do it)\b", re.I)
_FORGET_THAT = re.compile(r"\bforget (that|this|it)\s*[.!?]*\s*$", re.I)
_FORGET_X = re.compile(r"\bforget (?:that |about )?(.+)", re.I)
_EXPLAIN = re.compile(r"\bwhy (do|did|would) you (think|say|assume|suggest)\b|^\s*why\??\s*$", re.I)


def detect_intent(text: str) -> Intent:
    if _REJECT.search(text):
        return Intent("reject", raw=text)
    if _ACCEPT.search(text):
        return Intent("accept", raw=text)
    if _FORGET_THAT.search(text):
        return Intent("forget", target=None, raw=text)
    m = _FORGET_X.search(text)
    if m:
        return Intent("forget", target=m.group(1).strip(" .!?"), raw=text)
    if _EXPLAIN.search(text):
        return Intent("explain", raw=text)
    return Intent("none", raw=text)


class RuleExtractor:
    """Regex patterns for the handful of things drivers actually say."""

    _TEMP_PREF = re.compile(
        r"\bi (?:prefer|like|want)\b[^0-9]{0,40}?(\d{2}(?:\.\d)?)\s*(?:°|degrees|deg|c)?\b", re.I
    )
    _TEMP_SET = re.compile(
        r"\b(?:set|make|put)\b[^0-9]{0,30}?(\d{2}(?:\.\d)?)\s*(?:°|degrees|deg|c)?\b", re.I
    )
    _SCHOOL = re.compile(
        r"\bmy (?:kid'?s?|son'?s?|daughter'?s?|child'?s?|children'?s?) school is ([\w .'-]+)", re.I
    )
    _HOME = re.compile(r"\bmy home(?: address)? is ([\w .'-]+)", re.I)
    _WORK = re.compile(r"\b(?:my (?:work|office) is|i work at) ([\w .'-]+)", re.I)
    _NO_TOLLS = re.compile(r"\b(?:i )?(?:hate|dislike|avoid|don'?t (?:like|want))\b.{0,20}\btolls?\b", re.I)
    _TOLLS_OK = re.compile(r"\b(?:tolls? (?:are|is) fine|don'?t mind tolls?|take (?:the )?tolls?)\b", re.I)
    _ALLERGY = re.compile(r"\bi(?:'m| am) allergic to ([\w .'-]+)", re.I)
    _SENSOR = re.compile(
        r"\b(?:the |my )?(left|right) (front|rear) (?:parking )?sensor (?:is|seems|keeps|has been) ([\w ]+)", re.I
    )
    _TYRE = re.compile(r"\b(?:tyre|tire) pressure (?:warning|light|alert)\b", re.I)
    _REMEMBER = re.compile(r"\bremember (?:that )?(.+)", re.I)

    def extract(self, text: str) -> list[Candidate]:
        t = text.strip()
        out: list[Candidate] = []
        if m := self._TEMP_PREF.search(t):
            out.append(Candidate("climate", "cabin_temp", float(m.group(1)), kind="stated"))
        elif m := self._TEMP_SET.search(t):
            out.append(Candidate("climate", "cabin_temp", float(m.group(1)), kind="observed"))
        if m := self._SCHOOL.search(t):
            out.append(Candidate("school", "school_location", m.group(1).strip(" ."), kind="stated"))
        if m := self._HOME.search(t):
            out.append(Candidate("home_address", "home", m.group(1).strip(" ."), kind="stated"))
        if m := self._WORK.search(t):
            out.append(Candidate("note", "workplace", m.group(1).strip(" ."), kind="stated"))
        if self._NO_TOLLS.search(t):
            out.append(Candidate("route_prefs", "avoid_tolls", True, kind="stated"))
        elif self._TOLLS_OK.search(t):
            out.append(Candidate("route_prefs", "avoid_tolls", False, kind="stated"))
        if m := self._ALLERGY.search(t):
            out.append(Candidate("health", "allergy", m.group(1).strip(" ."), kind="stated"))
        if m := self._SENSOR.search(t):
            key = f"{m.group(1).lower()}_{m.group(2).lower()}_sensor"
            out.append(Candidate("vehicle_fault", key, m.group(3).strip(" ."), kind="stated", about="vehicle"))
        if self._TYRE.search(t):
            out.append(Candidate("vehicle_fault", "tyre_pressure", "warning reported", kind="stated", about="vehicle"))
        if not out and (m := self._REMEMBER.search(t)):
            note = m.group(1).strip(" .")
            out.append(Candidate("note", _slug(note), note, kind="stated"))
        return out


def _slug(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")[:40] or "note"


EXTRACTION_SCHEMA: dict[str, Any] = {
    "type": "object",
    "properties": {
        "candidates": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "category": {
                        "type": "string",
                        "enum": [
                            "climate", "seat", "media", "route_prefs", "home_address", "school",
                            "contacts", "health", "finance", "note", "vehicle_fault",
                        ],
                    },
                    "key": {"type": "string"},
                    "value": {"type": "string"},
                    "condition": {"type": ["string", "null"]},
                    "kind": {"type": "string", "enum": ["stated", "observed"]},
                    "about": {"type": "string", "enum": ["person", "vehicle"]},
                },
                "required": ["category", "key", "value", "condition", "kind", "about"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["candidates"],
    "additionalProperties": False,
}

EXTRACTION_SYSTEM = (
    "You extract durable memory from one utterance spoken to an in-car assistant. "
    "Return only facts worth remembering across trips: preferences, places, people, health, "
    "or complaints about the car itself (about=vehicle). A one-off command such as 'set it to 21' "
    "is kind=observed; a preference such as 'I like it at 21' is kind=stated. Use a short snake_case key. "
    "Use these keys when they fit, so memories line up with what the car records: cabin_temp (climate, degrees C), "
    "fan (climate), seat_position (seat) and media (media) only for a value the car can set directly; "
    "avoid_tolls (route_prefs, true or false), home (home_address), school_location (school), allergy (health). "
    "A like or dislike gets its own key, e.g. disliked_genre. "
    "Put a condition (e.g. 'season=winter', 'daypart=morning') only when the speaker states one. "
    "Return an empty list when nothing durable was said."
)


class LLMExtractor:
    """Model-backed extraction with the rule extractor as a safety net."""

    def __init__(self, llm: LLMClient, fallback: RuleExtractor | None = None):
        self.llm = llm
        self.fallback = fallback or RuleExtractor()

    def extract(self, text: str) -> list[Candidate]:
        try:
            data = self.llm.complete_json(EXTRACTION_SYSTEM, text, EXTRACTION_SCHEMA)
            items = data.get("candidates", []) if isinstance(data, dict) else []
            out = []
            for it in items:
                val: Any = it.get("value")
                if it.get("category") == "climate":
                    try:
                        val = float(str(val).rstrip("°Cc "))
                    except ValueError:
                        pass
                if it.get("category") == "route_prefs" and isinstance(val, str):
                    val = val.strip().lower() in {"true", "yes", "1"}
                out.append(
                    Candidate(
                        category=it["category"], key=_slug(str(it["key"])), value=val,
                        condition=it.get("condition") or None, kind=it.get("kind", "stated"),
                        about=it.get("about", "person"),
                    )
                )
            return out
        except Exception:
            return self.fallback.extract(text)


def _json_or_none(text: str) -> Any:
    try:
        return json.loads(text)
    except Exception:
        return None
