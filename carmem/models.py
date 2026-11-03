"""Data model shared by every layer."""

from __future__ import annotations

import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Optional

VEHICLE_SCOPE = "vehicle"
GUEST_PREFIX = "guest:"


class Tier(str, Enum):
    """How far the car may go on its own with a confirmed record.

    Ordered from most cautious to least. Demotion moves a record down this
    ladder; ``NEVER`` is a hard floor that demotion cannot change.
    """

    NEVER = "never"
    SILENT = "silent"
    SUGGEST = "suggest"
    ACT = "act"


TIER_ORDER = [Tier.NEVER, Tier.SILENT, Tier.SUGGEST, Tier.ACT]


class State(str, Enum):
    OBSERVED = "observed"
    CANDIDATE = "candidate"
    CONFIRMED = "confirmed"
    RETIRED = "retired"


def new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:12]}"


def iso(ts: datetime) -> str:
    return ts.replace(microsecond=0).isoformat()


def parse_iso(s: str) -> datetime:
    return datetime.fromisoformat(s)


def is_guest(person_id: str) -> bool:
    return person_id.startswith(GUEST_PREFIX)


@dataclass
class Person:
    id: str
    name: str
    roles: list[str] = field(default_factory=lambda: ["driver"])


@dataclass
class IdentitySignal:
    """One observation about who is in the car.

    ``kind`` is one of ``seat_profile``, ``phone``, ``key_fob`` (hard) or
    ``voice`` (soft). ``role`` is ``driver`` or ``passenger``.
    """

    kind: str
    value: str
    confidence: float = 1.0
    role: str = "driver"


@dataclass
class Occupancy:
    driver_id: str
    passenger_ids: list[str] = field(default_factory=list)
    is_guest: bool = False
    session_id: str = field(default_factory=lambda: new_id("ses"))
    resolved_from: list[str] = field(default_factory=list)

    @property
    def scopes(self) -> list[str]:
        return [self.driver_id] + list(self.passenger_ids)


@dataclass
class Trip:
    start: datetime
    end: datetime
    origin: str
    destination: str
    occupants: list[str] = field(default_factory=list)
    settings: dict[str, Any] = field(default_factory=dict)
    route: dict[str, Any] = field(default_factory=dict)
    distance_km: float = 0.0

    def summary(self) -> str:
        parts = [f"trip {self.origin} -> {self.destination}"]
        if self.route.get("tolls"):
            parts.append("via toll road")
        for k, v in sorted(self.settings.items()):
            parts.append(f"{k}={v}")
        return " ".join(parts)


@dataclass
class Utterance:
    ts: datetime
    text: str
    speaker_id: Optional[str] = None


@dataclass
class Episode:
    id: int
    scope: str
    kind: str  # trip | utterance | setting | sensor | system
    ts: datetime
    text: str
    payload: dict[str, Any] = field(default_factory=dict)


@dataclass
class Evidence:
    episode_id: int
    ts: str
    value: Any = None
    condition: Optional[str] = None
    weight: float = 1.0
    note: str = ""

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    @staticmethod
    def from_dict(d: dict[str, Any]) -> "Evidence":
        return Evidence(**d)


@dataclass
class Fact:
    """A distilled fact or preference, scoped to a person or to the vehicle."""

    id: str
    scope: str
    category: str
    key: str
    value: Any
    condition: Optional[str] = None
    state: State = State.OBSERVED
    source: str = "implicit"  # stated | implicit | sensor
    sensitivity: str = "personal"  # public | personal | sensitive
    support: list[Evidence] = field(default_factory=list)
    contradictions: list[Evidence] = field(default_factory=list)
    demotion: int = 0
    consecutive_no: int = 0
    created: str = ""
    updated: str = ""
    # Bi-temporal validity (Graphiti-style): when the fact started/stopped being true
    # in the world. A contradicted fact is never deleted; it gets a valid_until.
    valid_from: str = ""
    valid_until: Optional[str] = None

    @property
    def is_current(self) -> bool:
        return self.valid_until is None

    def describe(self) -> str:
        cond = f" (when {self.condition})" if self.condition else ""
        return f"{self.category}/{self.key} = {self.value}{cond}"


@dataclass
class Routine:
    """A recurring, actionable pattern mined from trips."""

    id: str
    scope: str
    signature: str
    dow: int  # 0=Monday
    minute_of_day: int
    destination: str
    origin: str = ""
    action: dict[str, Any] = field(default_factory=dict)
    state: State = State.OBSERVED
    support: list[Evidence] = field(default_factory=list)
    contradictions: list[Evidence] = field(default_factory=list)
    demotion: int = 0
    consecutive_no: int = 0
    first_seen: str = ""
    last_seen: str = ""
    stats: dict[str, Any] = field(default_factory=dict)

    @property
    def category(self) -> str:
        return "route"

    def describe(self) -> str:
        days = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
        h, m = divmod(self.minute_of_day, 60)
        frm = f" from {self.origin}" if self.origin else ""
        return f"{days[self.dow]} {h:02d}:{m:02d}{frm} -> {self.destination}"


@dataclass
class Proposal:
    """Something the car wants to do proactively, with the tier that governs it."""

    kind: str  # routine | fact
    record_id: str
    scope: str
    tier: Tier
    text: str
    action: dict[str, Any] = field(default_factory=dict)
    id: str = field(default_factory=lambda: new_id("prop"))

    def to_dict(self) -> dict[str, Any]:
        d = asdict(self)
        d["tier"] = self.tier.value
        return d
