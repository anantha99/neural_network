"""Identity layer: who is in the car right now.

Hard signals (seat profile, paired phone, key fob) identify a registered
person. Voice is a soft signal: it can choose between people the hard signals
already put in the car, but on its own it never unlocks a profile. With no
hard signal the driver is a guest with an ephemeral scope that is wiped at
ignition off.
"""

from __future__ import annotations

from datetime import datetime
from typing import Iterable

from .models import GUEST_PREFIX, IdentitySignal, Occupancy, Person, new_id
from .policy import Policy
from .store import Store

HARD_KINDS = {"seat_profile", "phone", "key_fob"}
SOFT_KINDS = {"voice"}


class IdentityResolver:
    def __init__(self, store: Store, policy: Policy):
        self.store = store
        self.policy = policy

    def enroll(self, person: Person, bindings: Iterable[tuple[str, str]] = ()) -> None:
        self.store.add_person(person)
        for kind, value in bindings:
            self.store.bind_identity(kind, value, person.id)

    def bind(self, kind: str, value: str, person_id: str) -> None:
        if self.store.get_person(person_id) is None:
            raise KeyError(f"unknown person {person_id}")
        self.store.bind_identity(kind, value, person_id)

    def resolve(self, signals: Iterable[IdentitySignal], now: datetime) -> Occupancy:
        signals = list(signals)
        driver_sigs = [s for s in signals if s.role == "driver"]
        passenger_sigs = [s for s in signals if s.role == "passenger"]

        driver_id, used = self._resolve_one(driver_sigs)
        is_guest = driver_id is None
        if is_guest:
            driver_id = f"{GUEST_PREFIX}{new_id('g')[2:]}"
            used.append("guest_default")

        passengers: list[str] = []
        for s in passenger_sigs:
            pid, _ = self._resolve_one([s])
            if pid and pid != driver_id and pid not in passengers:
                passengers.append(pid)

        occ = Occupancy(driver_id=driver_id, passenger_ids=passengers, is_guest=is_guest, resolved_from=used)
        self.store.audit(now, driver_id, "identity.resolved", {"signals": [s.kind for s in signals], "guest": is_guest})
        return occ

    def _resolve_one(self, signals: list[IdentitySignal]) -> tuple[str | None, list[str]]:
        """Return (person_id or None, kinds used)."""
        hard: dict[str, str] = {}
        for kind in self.policy.hard_signal_priority:
            for s in signals:
                if s.kind == kind and s.kind in HARD_KINDS:
                    pid = self.store.lookup_identity(s.kind, s.value)
                    if pid:
                        hard[kind] = pid
        voice = [s for s in signals if s.kind in SOFT_KINDS]

        if hard:
            candidates = list(dict.fromkeys(hard.values()))
            if len(candidates) == 1:
                return candidates[0], list(hard)
            # Conflicting hard signals (borrowed fob, shared phone). Let voice
            # break the tie among the hard candidates only; else highest priority wins.
            for v in sorted(voice, key=lambda s: -s.confidence):
                vid = self.store.lookup_identity("voice", v.value)
                if vid in candidates:
                    return vid, list(hard) + ["voice"]
            for kind in self.policy.hard_signal_priority:
                if kind in hard:
                    return hard[kind], [kind]

        # No hard signal. Voice alone identifies only above a (default impossible) threshold.
        for v in sorted(voice, key=lambda s: -s.confidence):
            if v.confidence >= self.policy.voice_alone_min_confidence:
                vid = self.store.lookup_identity("voice", v.value)
                if vid:
                    return vid, ["voice"]
        return None, []
