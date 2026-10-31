"""Read side: assemble the context packet that travels with a request.

The router decides where a request runs; this decides what goes with it. The
same packet renders differently for a local model and a cloud model: fields
the policy marks as masked are replaced by tokens, and the mapping stays on
the head unit so the cloud answer can be un-masked before it is spoken.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from .models import VEHICLE_SCOPE, Episode, Fact, Occupancy, Routine, State
from .policy import Policy
from .retrieval import Embedder, recency_weight, score, top_k
from .store import Store


@dataclass
class ContextPacket:
    driver_id: str
    driver_name: str
    destination: str  # local | cloud
    facts: list[Fact] = field(default_factory=list)
    routines: list[Routine] = field(default_factory=list)
    vehicle: list[Fact] = field(default_factory=list)
    episodes: list[Episode] = field(default_factory=list)
    masked: dict[str, str] = field(default_factory=dict)  # token -> original

    def render(self) -> str:
        lines = [f"[Driver memory for {self.driver_name} ({self.driver_id}); audience={self.destination}]"]
        if self.facts:
            lines.append("Known preferences and facts:")
            for f in self.facts:
                lines.append(f"- {self._fact_line(f)}")
        if self.routines:
            lines.append("Routines:")
            for r in self.routines:
                st = r.stats or {}
                lines.append(
                    f"- {self._mask_text(r.describe())} [{r.state.value}, {st.get('occurrences', 0)} times over "
                    f"{st.get('distinct_weeks', 0)} weeks]"
                )
        if self.vehicle:
            lines.append("About this car (shared by all occupants):")
            for f in self.vehicle:
                lines.append(f"- {f.key.replace('_', ' ')}: {f.value} [{f.source}]")
        if self.episodes:
            lines.append("Relevant recent episodes:")
            for e in self.episodes:
                lines.append(f"- {e.ts:%Y-%m-%d %H:%M} {e.kind}: {self._mask_text(e.text)}")
        if self.masked:
            lines.append(f"({len(self.masked)} personal field(s) masked for cloud; tokens are stable per session)")
        return "\n".join(lines)

    def unmask(self, text: str) -> str:
        for token, original in self.masked.items():
            text = text.replace(token, str(original))
        return text

    def to_dict(self) -> dict[str, Any]:
        return {
            "driver_id": self.driver_id, "destination": self.destination,
            "facts": [{"id": f.id, "text": self._fact_line(f), "source": f.source, "state": f.state.value} for f in self.facts],
            "routines": [{"id": r.id, "text": self._mask_text(r.describe()), "state": r.state.value, **r.stats} for r in self.routines],
            "vehicle": [{"id": f.id, "key": f.key, "value": f.value, "source": f.source} for f in self.vehicle],
            "episodes": [{"id": e.id, "ts": e.ts.isoformat(), "kind": e.kind, "text": self._mask_text(e.text)} for e in self.episodes],
            "masked_count": len(self.masked),
        }

    # internal
    def _fact_line(self, f: Fact) -> str:
        val = self._masked_value(f)
        cond = f" (when {f.condition.replace('=', ' is ')})" if f.condition else ""
        return f"{f.category}/{f.key} = {val}{cond} [{f.source}, {f.state.value}, id={f.id}]"

    def _masked_value(self, f: Fact) -> Any:
        for token, original in self.masked.items():
            if original == str(f.value) and token.startswith(f"<{f.category}"):
                return token
        return f.value

    def _mask_text(self, text: str) -> str:
        for token, original in self.masked.items():
            if original and str(original) in text:
                text = text.replace(str(original), token)
        return text


class ContextBuilder:
    def __init__(self, store: Store, policy: Policy, embedder: Embedder):
        self.store = store
        self.policy = policy
        self.embedder = embedder

    def build(
        self,
        occupancy: Occupancy,
        query: str = "",
        destination: str = "local",
        now: datetime | None = None,
        k_episodes: int = 5,
        episode_days: int = 42,
    ) -> ContextPacket:
        now = now or datetime.now()
        person = self.store.get_person(occupancy.driver_id)
        name = person.name if person else ("Guest" if occupancy.is_guest else occupancy.driver_id)
        packet = ContextPacket(occupancy.driver_id, name, destination)

        facts = self.store.facts([occupancy.driver_id], states=[State.CONFIRMED])
        packet.facts = _dedupe_facts(facts)
        packet.routines = self.store.routines([occupancy.driver_id], [State.CONFIRMED, State.CANDIDATE])
        packet.vehicle = self.store.facts([VEHICLE_SCOPE], states=[State.CONFIRMED])

        if query:
            qvec = self.embedder.embed(query)
            since = now - timedelta(days=episode_days)
            cands = self.store.episodes([occupancy.driver_id], since=since, limit=400, with_embeddings=True)
            hl = self.policy.retrieval_recency_half_life_days
            ranked = top_k(
                ((score(query, qvec, ep.text, vec) * recency_weight((now - ep.ts).total_seconds() / 86400, hl), ep)
                 for ep, vec in cands),
                k_episodes,
            )
            packet.episodes = [ep for s, ep in ranked if s > 0.05]
            # also rank facts by relevance so the most useful come first
            packet.facts.sort(key=lambda f: -score(query, qvec, f.describe(), None))

        if destination == "cloud":
            n = 0
            for f in packet.facts:
                if self.policy.masked_in_cloud(f.category, f.sensitivity):
                    n += 1
                    packet.masked[f"<{f.category}_{n}>"] = str(f.value)
        return packet


def _dedupe_facts(facts: list[Fact]) -> list[Fact]:
    """Stated beats implicit for the same key+condition; conditioned facts are kept alongside the base."""
    best: dict[tuple[str, str, str | None], Fact] = {}
    rank = {"stated": 2, "sensor": 1, "implicit": 0}
    for f in facts:
        key = (f.category, f.key, f.condition)
        if key not in best or rank.get(f.source, 0) > rank.get(best[key].source, 0):
            best[key] = f
    # drop an unconditioned implicit base when conditioned facts exist for the same key
    keys_with_cond = {(c, k) for (c, k, cond) in best if cond}
    out = []
    for (c, k, cond), f in best.items():
        if cond is None and f.source == "implicit" and (c, k) in keys_with_cond:
            continue
        out.append(f)
    return out
