"""CarMemory: the facade an assistant talks to.

One instance per vehicle. It owns the store, the identity resolver, the
consolidator and the context builder, and tracks the current driving session
(who is present, which proactive proposals are live, what 'no' refers to).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any, Callable, Iterable, Optional

from .consolidation import SETTING_CATEGORIES, Consolidator, norm_place
from .context import ContextBuilder, ContextPacket
from .extraction import Candidate, LLMExtractor, RuleExtractor, detect_intent
from .identity import IdentityResolver
from .llm.base import LLMClient
from .llm.tools import MEMORY_TOOLS, to_anthropic_tools, to_openai_tools
from .models import (
    VEHICLE_SCOPE,
    Fact,
    IdentitySignal,
    Occupancy,
    Person,
    Proposal,
    Routine,
    State,
    Tier,
    Trip,
    is_guest,
    iso,
)
from .policy import Policy
from .retrieval import Embedder, HashingEmbedder, keyword_overlap
from .store import Store


class _Session:
    def __init__(self, occupancy: Occupancy):
        self.occupancy = occupancy
        self.pending: list[Proposal] = []
        self.suppressed: set[str] = set()
        self.last_stated: list[str] = []
        self.started = datetime.now()


class CarMemory:
    def __init__(
        self,
        db_path: str = ":memory:",
        policy: Policy | None = None,
        llm: LLMClient | None = None,
        embedder: Embedder | None = None,
        clock: Callable[[], datetime] | None = None,
    ):
        self.policy = policy or Policy()
        self.store = Store(db_path)
        self.embedder = embedder or HashingEmbedder()
        self.clock = clock or datetime.now
        self.identity = IdentityResolver(self.store, self.policy)
        self.consolidator = Consolidator(self.store, self.policy, self.embedder, llm)
        self.context_builder = ContextBuilder(self.store, self.policy, self.embedder)
        self.extractor = LLMExtractor(llm) if llm is not None else RuleExtractor()
        self._session: Optional[_Session] = None

    # ------------------------------------------------------------------
    # identity / session
    # ------------------------------------------------------------------
    def enroll(self, person_id: str, name: str, bindings: Iterable[tuple[str, str]] = ()) -> Person:
        p = Person(person_id, name)
        self.identity.enroll(p, bindings)
        return p

    def bind(self, kind: str, value: str, person_id: str) -> None:
        self.identity.bind(kind, value, person_id)

    def ignition_on(self, signals: Iterable[IdentitySignal], now: datetime | None = None) -> Occupancy:
        now = now or self.clock()
        if self._session is not None:
            self.ignition_off(now)
        occ = self.identity.resolve(signals, now)
        self._session = _Session(occ)
        self._session.started = now
        return occ

    def ignition_off(self, now: datetime | None = None) -> dict[str, Any]:
        now = now or self.clock()
        result: dict[str, Any] = {"wiped": {}, "maintenance": {}}
        if self._session is not None:
            occ = self._session.occupancy
            for scope in occ.scopes:
                if is_guest(scope):
                    result["wiped"][scope] = self.store.purge_scope(scope)
                    self.store.audit(now, scope, "guest.wiped", result["wiped"][scope])
        # any stray guest scope (crash mid-trip, etc.) is wiped too
        for scope in self.store.scopes_present():
            if is_guest(scope) and scope not in result["wiped"]:
                result["wiped"][scope] = self.store.purge_scope(scope)
        self._session = None
        result["maintenance"] = self.consolidator.maintenance(now)
        return result

    @property
    def occupancy(self) -> Optional[Occupancy]:
        return self._session.occupancy if self._session else None

    def _driver(self) -> str:
        if self._session is None:
            raise RuntimeError("no session: call ignition_on() first")
        return self._session.occupancy.driver_id

    # ------------------------------------------------------------------
    # write side
    # ------------------------------------------------------------------
    def record_trip(self, trip: Trip, now: datetime | None = None) -> dict[str, Any]:
        now = now or trip.end
        scope = self._driver() if self._session else (trip.occupants[0] if trip.occupants else None)
        if scope is None:
            raise RuntimeError("record_trip needs a session or trip.occupants")
        text = trip.summary()
        ep = self.store.add_episode(
            scope, "trip", trip.start, text,
            {"origin": trip.origin, "destination": trip.destination, "end": iso(trip.end), "settings": trip.settings,
             "route": trip.route, "occupants": trip.occupants, "distance_km": trip.distance_km},
            self.embedder.embed(text),
        )
        out: dict[str, Any] = {"episode": ep.id, "facts": [], "routine": None, "accepted": []}
        for key, value in trip.settings.items():
            cat = SETTING_CATEGORIES.get(key)
            if cat is None or self.consolidator.fact_tombstoned(scope, cat, key, now):
                continue
            f = self.consolidator.observe_setting(scope, cat, key, value, ep, now)
            out["facts"].append({"id": f.id, "state": f.state.value, "fact": f.describe()})
        rt = self.consolidator.observe_trip(scope, trip, ep, now)
        if rt:
            out["routine"] = {"id": rt.id, "state": rt.state.value, "routine": rt.describe(), **rt.stats}
        # a proposal that the driver then did counts as accepted
        if self._session:
            for p in list(self._session.pending):
                if p.kind == "routine" and norm_place(p.action.get("destination", "")) == norm_place(trip.destination):
                    self.consolidator.accept(p, ep, now)
                    out["accepted"].append(p.record_id)
                    self._session.pending.remove(p)
        return out

    def record_vehicle_event(
        self, key: str, value: Any, category: str = "vehicle_fault", now: datetime | None = None, text: str | None = None
    ) -> Fact:
        now = now or self.clock()
        text = text or f"vehicle {category} {key}: {value}"
        ep = self.store.add_episode(VEHICLE_SCOPE, "sensor", now, text, {"key": key, "value": value}, self.embedder.embed(text))
        return self.consolidator.sensor_fact(category, key, value, ep, now)

    def hear(self, text: str, speaker_id: str | None = None, now: datetime | None = None) -> dict[str, Any]:
        """Process one utterance: driving-time intents first, then extraction."""
        now = now or self.clock()
        scope = speaker_id or self._driver()
        ep = self.store.add_episode(scope, "utterance", now, text, {"speaker": scope}, self.embedder.embed(text))
        intent = detect_intent(text)
        result: dict[str, Any] = {"intent": intent.name, "stored": [], "episode": ep.id}

        if intent.name == "reject":
            target = self._active_proposal(prefer=Tier.SUGGEST)
            if target:
                result["correction"] = self.consolidator.reject(target, ep, now)
                self._session.suppressed.add(target.record_id)
                self._session.pending.remove(target)
            else:
                result["correction"] = {"applied": False, "reason": "nothing pending"}
            return result
        if intent.name == "accept":
            target = self._active_proposal(prefer=Tier.SUGGEST)
            if target:
                result["correction"] = self.consolidator.accept(target, ep, now)
                self._session.pending.remove(target)
            return result
        if intent.name == "forget":
            if intent.target is None:
                target = self._active_proposal(prefer=Tier.SUGGEST)
                rid = target.record_id if target else (self._session.last_stated[-1] if self._session and self._session.last_stated else None)
                result["forget"] = self.forget(rid, now) if rid else {"forgot": None, "reason": "nothing to forget"}
                if target and self._session:
                    self._session.pending.remove(target)
                    self._session.suppressed.add(target.record_id)
            else:
                result["forget"] = self.forget(intent.target, now, scope=scope)
            return result
        if intent.name == "explain":
            target = self._active_proposal(prefer=Tier.SUGGEST)
            rid = target.record_id if target else (self._session.last_stated[-1] if self._session and self._session.last_stated else None)
            result["explanation"] = self.explain(rid) if rid else {"record": None}
            return result

        for c in self.extractor.extract(text):
            result["stored"].append(self._apply_candidate(scope, c, ep, now))
        return result

    def _apply_candidate(self, scope: str, c: Candidate, ep, now: datetime) -> dict[str, Any]:
        if c.kind == "observed":
            if self.consolidator.fact_tombstoned(scope, c.category, c.key, now):
                return {"skipped": "tombstoned", "fact": f"{c.category}/{c.key}"}
            f = self.consolidator.observe_setting(scope, c.category, c.key, c.value, ep, now)
        else:
            # An explicit statement overrides an earlier 'forget' of the same key.
            sig = f"{c.category}/{c.key}"
            if self.store.is_tombstoned(scope, sig, now):
                self.store.add_tombstone(scope, sig, now, "restated by driver")
            f = self.consolidator.state_fact(scope, c, ep, now)
            if self._session and scope in self._session.occupancy.scopes:
                self._session.last_stated.append(f.id)
        return {"id": f.id, "state": f.state.value, "fact": f.describe(), "source": f.source}

    # ------------------------------------------------------------------
    # acting
    # ------------------------------------------------------------------
    def proactive(self, now: datetime | None = None, location: str | None = None) -> list[Proposal]:
        """Ask what the car would do now. Pass the car's current place as ``location`` when known."""
        now = now or self.clock()
        if self._session is None:
            return []
        props = self.consolidator.proposals(self._driver(), now, self._session.suppressed, location)
        # keep earlier pending proposals that are still unresolved
        known = {p.record_id for p in props}
        self._session.pending = [p for p in self._session.pending if p.record_id not in known] + props
        if props:
            self.store.audit(now, self._driver(), "proactive", {"proposals": [p.to_dict() for p in props]})
        return props

    def _active_proposal(self, prefer: Tier) -> Optional[Proposal]:
        if not self._session or not self._session.pending:
            return None
        preferred = [p for p in self._session.pending if p.tier == prefer]
        return (preferred or self._session.pending)[-1]

    def correct(self, answer: str, now: datetime | None = None) -> dict[str, Any]:
        return self.hear("no" if answer.strip().lower().startswith("n") else "yes", now=now)

    # ------------------------------------------------------------------
    # read side
    # ------------------------------------------------------------------
    def context(self, query: str = "", destination: str = "local", now: datetime | None = None) -> ContextPacket:
        return self.context_builder.build(self._session.occupancy if self._session else Occupancy("nobody", is_guest=True),
                                          query, destination, now or self.clock())

    def recall(self, query: str, k: int = 6, now: datetime | None = None) -> dict[str, Any]:
        packet = self.context(query, "local", now)
        d = packet.to_dict()
        d["facts"] = d["facts"][:k]
        d["episodes"] = d["episodes"][:k]
        return d

    def fact_history(self, category: str, key: str, scope: str | None = None) -> list[dict[str, Any]]:
        """Timeline of a fact: every value it has had, with validity intervals."""
        scope = scope or self._driver()
        return [
            {"id": f.id, "value": f.value, "condition": f.condition, "source": f.source,
             "valid_from": f.valid_from, "valid_until": f.valid_until, "state": f.state.value}
            for f in self.store.fact_timeline(scope, category, key)
        ]

    def explain(self, record_id: str) -> dict[str, Any]:
        rec: Fact | Routine | None = self.store.get_fact(record_id) or self.store.get_routine(record_id)
        if rec is None:
            return {"record": None}
        def ev(e):
            ep = self.store.get_episode(e.episode_id)
            return {"ts": e.ts, "note": e.note, "value": e.value,
                    "episode": ep.text if ep else "(episode expired from episodic memory)"}
        return {
            "record": rec.id, "kind": "routine" if isinstance(rec, Routine) else "fact", "description": rec.describe(),
            "state": rec.state.value, "source": getattr(rec, "source", "trips"),
            "valid_from": getattr(rec, "valid_from", None), "valid_until": getattr(rec, "valid_until", None),
            "because": [ev(e) for e in rec.support[-10:]],
            "against": [ev(e) for e in rec.contradictions[-10:]],
            "tier": self.policy.effective_tier(rec.category, rec.demotion).value,
        }

    def forget(self, target: str, now: datetime | None = None, scope: str | None = None) -> dict[str, Any]:
        """Forget by record id, or by free-text search within the speaker's scope."""
        now = now or self.clock()
        if self.store.get_fact(target) or self.store.get_routine(target):
            return self.consolidator.forget(target, now)
        scope = scope or (self._driver() if self._session else None)
        if scope is None:
            return {"forgot": None, "reason": "no scope"}
        best, best_s = None, 0.0
        for f in self.store.facts([scope]):
            s = keyword_overlap(target, f"{f.category} {f.key} {f.value} {f.condition or ''}".replace("_", " "))
            if s > best_s:
                best, best_s = f.id, s
        for r in self.store.routines([scope]):
            s = keyword_overlap(target, f"{r.destination} {r.describe()} route trip routine go going")
            if s > best_s:
                best, best_s = r.id, s
        if best is None or best_s < 0.2:
            return {"forgot": None, "reason": "no matching memory", "query": target}
        return self.consolidator.forget(best, now)

    # ------------------------------------------------------------------
    # tool-calling surface
    # ------------------------------------------------------------------
    def tool_specs(self, flavor: str = "neutral") -> list[dict[str, Any]]:
        if flavor == "anthropic":
            return to_anthropic_tools()
        if flavor == "openai":
            return to_openai_tools()
        return MEMORY_TOOLS

    def handle_tool(self, name: str, args: dict[str, Any] | None = None) -> dict[str, Any]:
        args = args or {}
        if name == "memory_recall":
            return self.recall(args["query"], int(args.get("k", 6)))
        if name == "memory_remember":
            return self.hear(args["text"])
        if name == "memory_forget":
            return self.forget(args["target"])
        if name == "memory_correct":
            return self.correct(args["answer"])
        if name == "memory_explain":
            return self.explain(args["record_id"])
        if name == "memory_proactive":
            return {"proposals": [p.to_dict() for p in self.proactive(location=args.get("location"))]}
        raise KeyError(f"unknown tool {name}")

    # ------------------------------------------------------------------
    # misc
    # ------------------------------------------------------------------
    def maintenance(self, now: datetime | None = None) -> dict[str, int]:
        return self.consolidator.maintenance(now or self.clock())

    def snapshot(self, for_cloud: bool = False) -> dict[str, Any]:
        """Semantic + procedural layers for sync. Episodic never leaves the car."""
        facts = []
        for f in self.store.facts(None, states=[State.CONFIRMED, State.CANDIDATE]):
            if is_guest(f.scope):
                continue
            if for_cloud and self.policy.masked_in_cloud(f.category, f.sensitivity):
                continue
            facts.append({"id": f.id, "scope": f.scope, "fact": f.describe(), "source": f.source, "state": f.state.value})
        routines = [
            {"id": r.id, "scope": r.scope, "routine": r.describe(), "state": r.state.value, **r.stats}
            for r in self.store.routines(None, [State.CONFIRMED, State.CANDIDATE]) if not is_guest(r.scope)
        ]
        return {"facts": facts, "routines": routines, "tombstones": self.store.tombstones()}

    def audit(self, limit: int = 50) -> list[dict[str, Any]]:
        return self.store.audit_log(limit)

    def close(self) -> None:
        self.store.close()
