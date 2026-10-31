"""The editable consolidation policy.

Everything that decides *what gets promoted* and *how far the car may act*
lives here as plain data, deliberately separate from any model. Load it from
JSON, edit a number, reload - no retraining.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any

from .models import TIER_ORDER, Tier

DEFAULT_TIERS: dict[str, str] = {
    # cabin comfort: trivially reversible -> act
    "climate": "act",
    "seat": "act",
    "media": "act",
    # navigation: medium cost -> one-line suggestion, silence means no
    "route": "suggest",
    "route_prefs": "silent",
    # things that reach other people or are sensitive -> never proactive
    "contacts": "never",
    "health": "never",
    "home_address": "silent",
    "school": "silent",
    "note": "silent",
    "vehicle_fault": "silent",
}

DEFAULT_SENSITIVITY: dict[str, str] = {
    "health": "sensitive",
    "contacts": "sensitive",
    "home_address": "sensitive",
    "school": "sensitive",
    "finance": "sensitive",
    "climate": "public",
    "seat": "public",
    "media": "personal",
    "route": "personal",
    "route_prefs": "personal",
    "note": "personal",
    "vehicle_fault": "public",
}


@dataclass
class Policy:
    # --- routines (mined, so strict) ---
    routine_window_days: int = 56
    routine_candidate_min_occurrences: int = 2
    routine_confirm_min_occurrences: int = 3
    routine_confirm_min_distinct_weeks: int = 3
    routine_confirm_min_ratio: float = 0.7
    routine_contradiction_window_days: int = 14
    routine_slot_tolerance_min: int = 45
    routine_retire_after_days: int = 42
    guest_routines: bool = False

    # --- implicit facts (observed settings) ---
    fact_window_days: int = 56
    fact_implicit_min_support: int = 3
    fact_confirm_ratio: float = 0.7
    fact_split_conditions: list[str] = field(default_factory=lambda: ["season", "daypart"])

    # --- stated facts ---
    fact_confirm_on_statement: bool = True

    # --- reconcile (Mem0-style add/update/noop) for free-text categories ---
    reconcile_categories: list[str] = field(default_factory=lambda: ["note"])
    reconcile_top_k: int = 5
    reconcile_noop_similarity: float = 0.8
    reconcile_update_similarity: float = 0.45

    # --- retrieval ---
    retrieval_recency_half_life_days: float = 14.0

    # --- correction ---
    no_weight: float = 1.0
    demote_after_consecutive_no: int = 2
    tombstone_days: int = 90

    # --- vehicle layer ---
    vehicle_min_distinct_persons: int = 2

    # --- retention ---
    episodic_retention_days: int = 42

    # --- identity ---
    hard_signal_priority: list[str] = field(default_factory=lambda: ["seat_profile", "phone", "key_fob"])
    voice_alone_min_confidence: float = 1.01  # >1 means voice alone never identifies

    # --- action tiers and masking ---
    tiers: dict[str, str] = field(default_factory=lambda: dict(DEFAULT_TIERS))
    sensitivity: dict[str, str] = field(default_factory=lambda: dict(DEFAULT_SENSITIVITY))
    mask_in_cloud_categories: list[str] = field(
        default_factory=lambda: ["health", "contacts", "home_address", "school", "finance"]
    )
    mask_in_cloud_sensitivity: list[str] = field(default_factory=lambda: ["sensitive"])

    # ------------------------------------------------------------------
    def tier_for(self, category: str) -> Tier:
        return Tier(self.tiers.get(category, "silent"))

    def sensitivity_for(self, category: str) -> str:
        return self.sensitivity.get(category, "personal")

    def effective_tier(self, category: str, demotion: int) -> Tier:
        base = self.tier_for(category)
        if base == Tier.NEVER:
            return base
        idx = TIER_ORDER.index(base) - max(0, demotion)
        idx = max(idx, TIER_ORDER.index(Tier.SILENT))
        return TIER_ORDER[idx]

    def masked_in_cloud(self, category: str, sensitivity: str) -> bool:
        return category in self.mask_in_cloud_categories or sensitivity in self.mask_in_cloud_sensitivity

    # ------------------------------------------------------------------
    def to_dict(self) -> dict[str, Any]:
        return asdict(self)

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), indent=2, sort_keys=True)

    @classmethod
    def from_dict(cls, d: dict[str, Any]) -> "Policy":
        known = {k: v for k, v in d.items() if k in cls.__dataclass_fields__}
        return cls(**known)

    @classmethod
    def load(cls, path: str | Path) -> "Policy":
        return cls.from_dict(json.loads(Path(path).read_text()))

    def save(self, path: str | Path) -> None:
        Path(path).write_text(self.to_json())
