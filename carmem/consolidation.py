"""The write side: what gets promoted, when the car may act, how 'no' corrects it.

Every candidate is a record with a lifecycle (observed -> candidate -> confirmed
-> retired), a support list and a contradiction list, each entry pointing back
into episodic memory. Transitions are deterministic rules over those lists,
with every threshold read from :class:`carmem.policy.Policy`.
"""

from __future__ import annotations

from collections import Counter, defaultdict
from datetime import datetime, timedelta
from typing import Any, Optional

from .extraction import Candidate
from .models import (
    VEHICLE_SCOPE,
    Episode,
    Evidence,
    Fact,
    Proposal,
    Routine,
    State,
    Tier,
    Trip,
    is_guest,
    iso,
    new_id,
    parse_iso,
)
from .policy import Policy
from .store import Store

DAY_NAMES = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]


def season_of(ts: datetime) -> str:
    m = ts.month
    if m in (12, 1, 2):
        return "winter"
    if m in (3, 4, 5):
        return "spring"
    if m in (6, 7, 8):
        return "summer"
    return "autumn"


def daypart_of(ts: datetime) -> str:
    h = ts.hour
    if 5 <= h < 11:
        return "morning"
    if 11 <= h < 17:
        return "afternoon"
    if 17 <= h < 22:
        return "evening"
    return "night"


def conditions_for(ts: datetime) -> dict[str, str]:
    return {"season": season_of(ts), "daypart": daypart_of(ts)}


def norm_place(p: str) -> str:
    return " ".join(p.lower().split())


def routine_signature(dow: int, destination: str) -> str:
    return f"{dow}:{norm_place(destination)}"


def routine_tombstone_key(destination: str) -> str:
    """'Forget that' means the destination, every weekday - not one slot."""
    return f"route:{norm_place(destination)}"


class Consolidator:
    def __init__(self, store: Store, policy: Policy):
        self.store = store
        self.policy = policy

    # ------------------------------------------------------------------
    # semantic layer: stated facts
    # ------------------------------------------------------------------
    def state_fact(self, scope: str, c: Candidate, ep: Episode, now: datetime) -> Fact:
        """A driver told us something. Stated facts are confirmed immediately."""
        sens = self.policy.sensitivity_for(c.category)
        existing = [
            f for f in self.store.facts([scope], c.category, c.key, [State.CONFIRMED, State.CANDIDATE])
            if f.source == "stated" and f.condition == c.condition
        ]
        ev = Evidence(ep.id, iso(ep.ts), c.value, c.condition, note="stated")
        for old in existing:
            if old.value == c.value:
                old.support.append(ev)
                old.updated = iso(now)
                self.store.upsert_fact(old)
                return old
            # A new statement supersedes the old one, but we keep the trail.
            old.state = State.RETIRED
            old.contradictions.append(ev)
            old.updated = iso(now)
            self.store.upsert_fact(old)
            self.store.audit(now, scope, "fact.superseded", {"old": old.id, "value": c.value})
        f = Fact(
            id=new_id("fact"), scope=scope, category=c.category, key=c.key, value=c.value, condition=c.condition,
            state=State.CONFIRMED if self.policy.fact_confirm_on_statement else State.CANDIDATE,
            source="stated", sensitivity=sens, support=[ev], created=iso(now), updated=iso(now),
        )
        self.store.upsert_fact(f)
        self.store.audit(now, scope, "fact.stated", {"id": f.id, "fact": f.describe()})
        if c.about == "vehicle":
            self._maybe_promote_vehicle(c.category, c.key, now)
        return f

    # ------------------------------------------------------------------
    # semantic layer: implicit facts from observed settings
    # ------------------------------------------------------------------
    def observe_setting(self, scope: str, category: str, key: str, value: Any, ep: Episode, now: datetime) -> Fact:
        """We saw the driver do something (set 21°C). Accumulate, confirm on consistency."""
        conds = conditions_for(ep.ts)
        base = self._implicit_base(scope, category, key)
        if base is None:
            base = Fact(
                id=new_id("fact"), scope=scope, category=category, key=key, value=value, condition=None,
                state=State.OBSERVED, source="implicit", sensitivity=self.policy.sensitivity_for(category),
                created=iso(now), updated=iso(now),
            )
        base.support.append(Evidence(ep.id, iso(ep.ts), value, _cond_str(conds), note="observed"))
        base.updated = iso(now)
        self._evaluate_implicit(base, now)
        return base

    def _implicit_base(self, scope: str, category: str, key: str) -> Optional[Fact]:
        for f in self.store.facts([scope], category, key):
            if f.source == "implicit" and f.condition is None and f.state != State.RETIRED:
                return f
        return None

    def _evaluate_implicit(self, base: Fact, now: datetime) -> None:
        window = now - timedelta(days=self.policy.fact_window_days)
        recent = [e for e in base.support if parse_iso(e.ts) >= window]
        counts = Counter(_norm_value(e.value) for e in recent)
        n = sum(counts.values())
        if n == 0:
            self.store.upsert_fact(base)
            return
        dominant, dn = counts.most_common(1)[0]
        ratio = dn / n
        if n >= self.policy.fact_implicit_min_support and ratio >= self.policy.fact_confirm_ratio:
            changed = base.state != State.CONFIRMED or _norm_value(base.value) != dominant
            base.value = _denorm(dominant, recent)
            base.state = State.CONFIRMED
            self.store.upsert_fact(base)
            if changed:
                self.store.audit(now, base.scope, "fact.confirmed", {"id": base.id, "fact": base.describe(), "n": n})
            return
        if n >= self.policy.fact_implicit_min_support and self._try_split(base, recent, now):
            return
        base.value = _denorm(dominant, recent)
        base.state = State.CANDIDATE if n >= 2 else State.OBSERVED
        self.store.upsert_fact(base)

    def _try_split(self, base: Fact, recent: list[Evidence], now: datetime) -> bool:
        """Values disagree. Propose a condition that separates them cleanly.

        Deterministic version of 'the model proposes a condition, the rule
        accepts the split only if both halves stand on their own'.
        """
        for cond_name in self.policy.fact_split_conditions:
            groups: dict[str, list[Evidence]] = defaultdict(list)
            for e in recent:
                cv = _cond_get(e.condition, cond_name)
                if cv is not None:
                    groups[f"{cond_name}={cv}"].append(e)
            if len(groups) < 2:
                continue
            resolved: dict[str, Any] = {}
            ok = True
            for g, evs in groups.items():
                c = Counter(_norm_value(e.value) for e in evs)
                dom, dn = c.most_common(1)[0]
                if len(evs) < self.policy.fact_implicit_min_support or dn / len(evs) < self.policy.fact_confirm_ratio:
                    ok = False
                    break
                resolved[g] = _denorm(dom, evs)
            if not ok or len(set(map(_norm_value, resolved.values()))) < 2:
                continue
            # accept the split
            for g, evs in groups.items():
                existing = next(
                    (f for f in self.store.facts([base.scope], base.category, base.key)
                     if f.source == "implicit" and f.condition == g and f.state != State.RETIRED), None)
                f = existing or Fact(
                    id=new_id("fact"), scope=base.scope, category=base.category, key=base.key, value=resolved[g],
                    condition=g, state=State.CONFIRMED, source="implicit", sensitivity=base.sensitivity,
                    created=iso(now), updated=iso(now),
                )
                f.value, f.state, f.support, f.updated = resolved[g], State.CONFIRMED, list(evs), iso(now)
                self.store.upsert_fact(f)
            base.state = State.CANDIDATE  # keep accumulating; conditioned facts carry the answer
            base.updated = iso(now)
            self.store.upsert_fact(base)
            self.store.audit(now, base.scope, "fact.split", {"id": base.id, "by": cond_name, "values": resolved})
            return True
        return False

    # ------------------------------------------------------------------
    # vehicle layer
    # ------------------------------------------------------------------
    def sensor_fact(self, category: str, key: str, value: Any, ep: Episode, now: datetime) -> Fact:
        """Sensor-sourced facts are vehicle-scoped and confirmed on arrival."""
        existing = next(
            (f for f in self.store.facts([VEHICLE_SCOPE], category, key) if f.state != State.RETIRED), None)
        ev = Evidence(ep.id, iso(ep.ts), value, note="sensor")
        if existing:
            existing.value, existing.state, existing.updated = value, State.CONFIRMED, iso(now)
            existing.support.append(ev)
            self.store.upsert_fact(existing)
            return existing
        f = Fact(
            id=new_id("fact"), scope=VEHICLE_SCOPE, category=category, key=key, value=value, state=State.CONFIRMED,
            source="sensor", sensitivity="public", support=[ev], created=iso(now), updated=iso(now),
        )
        self.store.upsert_fact(f)
        self.store.audit(now, VEHICLE_SCOPE, "vehicle.fact", {"id": f.id, "fact": f.describe()})
        return f

    def _maybe_promote_vehicle(self, category: str, key: str, now: datetime) -> Optional[Fact]:
        """A person-stated claim about the car becomes vehicle memory only when
        a second person (or a sensor) agrees."""
        claims = [
            f for f in self.store.facts(None, category, key, [State.CONFIRMED])
            if f.scope != VEHICLE_SCOPE and not is_guest(f.scope)
        ]
        persons = {f.scope for f in claims}
        if len(persons) < self.policy.vehicle_min_distinct_persons:
            return None
        if any(f.scope == VEHICLE_SCOPE and f.state == State.CONFIRMED for f in self.store.facts([VEHICLE_SCOPE], category, key)):
            return None
        latest = max(claims, key=lambda f: f.updated)
        f = Fact(
            id=new_id("fact"), scope=VEHICLE_SCOPE, category=category, key=key, value=latest.value,
            state=State.CONFIRMED, source="stated", sensitivity="public",
            support=[e for c in claims for e in c.support], created=iso(now), updated=iso(now),
        )
        self.store.upsert_fact(f)
        self.store.audit(now, VEHICLE_SCOPE, "vehicle.promoted", {"id": f.id, "from": sorted(persons)})
        return f

    # ------------------------------------------------------------------
    # procedural layer: routines mined from trips
    # ------------------------------------------------------------------
    def observe_trip(self, scope: str, trip: Trip, ep: Episode, now: datetime) -> Optional[Routine]:
        if is_guest(scope) and not self.policy.guest_routines:
            return None
        dow = trip.start.weekday()
        mod = trip.start.hour * 60 + trip.start.minute
        sig = routine_signature(dow, trip.destination)
        if self.store.is_tombstoned(scope, routine_tombstone_key(trip.destination), now):
            self.store.audit(now, scope, "routine.blocked_by_tombstone", {"signature": sig})
            self._contradict_slot(scope, trip, ep, now, exclude=None)
            return None

        tol = self.policy.routine_slot_tolerance_min
        same = [
            r for r in self.store.routines([scope])
            if r.signature == sig and r.state != State.RETIRED and abs(r.minute_of_day - mod) <= tol
        ]
        rt = same[0] if same else Routine(
            id=new_id("rt"), scope=scope, signature=sig, dow=dow, minute_of_day=mod,
            destination=trip.destination, origin=trip.origin,
            action={"type": "navigate", "destination": trip.destination},
            first_seen=iso(trip.start), last_seen=iso(trip.start),
        )
        rt.support.append(Evidence(ep.id, iso(trip.start), trip.destination, condition=trip.origin, note="trip"))
        rt.last_seen = iso(trip.start)
        rt.consecutive_no = 0  # the driver did the thing: reset the 'no' streak
        # the typical origin is the most common one in recent support
        origins = Counter(norm_place(e.condition or "") for e in rt.support[-10:] if e.condition)
        if origins:
            top = origins.most_common(1)[0][0]
            rt.origin = next((e.condition for e in reversed(rt.support) if norm_place(e.condition or "") == top), rt.origin)
        # doing the trip today cancels any 'went elsewhere' recorded earlier today (chained trips)
        today = trip.start.date()
        rt.contradictions = [
            e for e in rt.contradictions if not (e.note == "went elsewhere" and parse_iso(e.ts).date() == today)
        ]
        # Keep the representative time as the mean of recent support.
        mins = [parse_iso(e.ts).hour * 60 + parse_iso(e.ts).minute for e in rt.support[-10:]]
        rt.minute_of_day = int(round(sum(mins) / len(mins)))
        self.evaluate_routine(rt, now)
        self._contradict_slot(scope, trip, ep, now, exclude=rt.id)
        return rt

    def _contradict_slot(self, scope: str, trip: Trip, ep: Episode, now: datetime, exclude: Optional[str]) -> None:
        """Going somewhere else in a routine's slot is evidence against that routine."""
        mod = trip.start.hour * 60 + trip.start.minute
        tol = self.policy.routine_slot_tolerance_min
        today = trip.start.date()
        for r in self.store.routines([scope], [State.CANDIDATE, State.CONFIRMED]):
            if r.id == exclude or r.dow != trip.start.weekday() or abs(r.minute_of_day - mod) > tol:
                continue
            if norm_place(r.destination) == norm_place(trip.destination):
                continue
            if r.origin and trip.origin and norm_place(r.origin) != norm_place(trip.origin):
                continue  # left from somewhere else: not a substitute for this routine
            if any(e.note == "trip" and parse_iso(e.ts).date() == today for e in r.support):
                continue  # already did the routine today; this is a chained trip
            r.contradictions.append(Evidence(ep.id, iso(trip.start), trip.destination, note="went elsewhere"))
            self.evaluate_routine(r, now)

    def evaluate_routine(self, rt: Routine, now: datetime) -> Routine:
        p = self.policy
        window = now - timedelta(days=p.routine_window_days)
        sup = [parse_iso(e.ts) for e in rt.support if parse_iso(e.ts) >= window]
        occ = len(sup)
        weeks = len({t.isocalendar()[:2] for t in sup})
        eligible = _count_weekday(min(sup), now, rt.dow) if sup else 0
        ratio = occ / eligible if eligible else 0.0
        recent_contra = [
            e for e in rt.contradictions
            if parse_iso(e.ts) >= now - timedelta(days=p.routine_contradiction_window_days)
        ]
        prev = rt.state
        last_seen = parse_iso(rt.last_seen) if rt.last_seen else None
        if last_seen and now - last_seen > timedelta(days=p.routine_retire_after_days):
            rt.state = State.RETIRED
        elif (
            occ >= p.routine_confirm_min_occurrences
            and weeks >= p.routine_confirm_min_distinct_weeks
            and ratio >= p.routine_confirm_min_ratio
            and not recent_contra
        ):
            rt.state = State.CONFIRMED
        elif occ >= p.routine_candidate_min_occurrences:
            rt.state = State.CANDIDATE
        else:
            rt.state = State.OBSERVED
        rt.stats = {
            "occurrences": occ, "distinct_weeks": weeks, "eligible_slots": eligible,
            "ratio": round(ratio, 3), "recent_contradictions": len(recent_contra),
        }
        self.store.upsert_routine(rt)
        if rt.state != prev:
            self.store.audit(now, rt.scope, "routine.state", {"id": rt.id, "from": prev.value, "to": rt.state.value,
                                                             "routine": rt.describe(), **rt.stats})
        return rt

    # ------------------------------------------------------------------
    # acting: proposals
    # ------------------------------------------------------------------
    def proposals(self, scope: str, now: datetime, suppressed: set[str], location: str | None = None) -> list[Proposal]:
        """What the car would do right now. ``location`` is where the car is; when
        given, a route is proposed only from the place the routine usually starts."""
        out: list[Proposal] = []
        if is_guest(scope):
            return out
        tol = self.policy.routine_slot_tolerance_min
        mod = now.hour * 60 + now.minute
        for r in self.store.routines([scope], [State.CONFIRMED]):
            if r.id in suppressed or r.dow != now.weekday() or abs(r.minute_of_day - mod) > tol:
                continue
            if location and r.origin and norm_place(location) != norm_place(r.origin):
                continue
            if location and norm_place(location) == norm_place(r.destination):
                continue
            tier = self.policy.effective_tier(r.category, r.demotion)
            if tier in (Tier.NEVER, Tier.SILENT):
                continue
            text = (
                f"Heading to {r.destination}? I've queued the route; say no to skip."
                if tier == Tier.SUGGEST else f"Navigating to {r.destination}."
            )
            out.append(Proposal("routine", r.id, scope, tier, text, dict(r.action)))
        conds = conditions_for(now)
        for f in self.store.facts([scope], states=[State.CONFIRMED]):
            if f.id in suppressed or f.category not in ("climate", "seat", "media"):
                continue
            if f.condition and not _cond_matches(f.condition, conds):
                continue
            tier = self.policy.effective_tier(f.category, f.demotion)
            if tier in (Tier.NEVER, Tier.SILENT):
                continue
            # prefer a conditioned fact over its unconditioned base for the same key
            if f.condition is None and any(
                g.condition and _cond_matches(g.condition, conds) and g.key == f.key
                for g in self.store.facts([scope], f.category, f.key, [State.CONFIRMED])
            ):
                continue
            text = f"Setting {f.key.replace('_', ' ')} to {f.value}."
            out.append(Proposal("fact", f.id, scope, tier, text, {"type": "set", "key": f.key, "value": f.value}))
        return out

    # ------------------------------------------------------------------
    # correction
    # ------------------------------------------------------------------
    def reject(self, prop: Proposal, ep: Episode, now: datetime) -> dict[str, Any]:
        rec = self._record(prop)
        if rec is None:
            return {"applied": False}
        rec.consecutive_no += 1
        rec.contradictions.append(Evidence(ep.id, iso(ep.ts), None, weight=self.policy.no_weight, note="said no"))
        demoted = False
        if rec.consecutive_no >= self.policy.demote_after_consecutive_no:
            rec.demotion += 1
            rec.consecutive_no = 0
            demoted = True
        if isinstance(rec, Routine):
            self.evaluate_routine(rec, now)
        else:
            rec.updated = iso(now)
            self.store.upsert_fact(rec)
        tier = self.policy.effective_tier(rec.category, rec.demotion)
        self.store.audit(now, prop.scope, "correction.no", {"id": rec.id, "demoted": demoted, "tier": tier.value})
        return {"applied": True, "record": rec.id, "demoted": demoted, "tier": tier.value}

    def accept(self, prop: Proposal, ep: Episode, now: datetime) -> dict[str, Any]:
        rec = self._record(prop)
        if rec is None:
            return {"applied": False}
        rec.consecutive_no = 0
        rec.support.append(Evidence(ep.id, iso(ep.ts), None, note="accepted"))
        if isinstance(rec, Routine):
            self.evaluate_routine(rec, now)
        else:
            rec.updated = iso(now)
            self.store.upsert_fact(rec)
        return {"applied": True, "record": rec.id}

    def forget(self, record_id: str, now: datetime, reason: str = "driver asked") -> dict[str, Any]:
        """Delete and tombstone so the miner cannot quietly re-derive it next week."""
        expires = now + timedelta(days=self.policy.tombstone_days)
        rt = self.store.get_routine(record_id)
        if rt:
            removed = []
            for other in self.store.routines([rt.scope]):
                if norm_place(other.destination) == norm_place(rt.destination):
                    self.store.delete_routine(other.id)
                    removed.append(other.id)
            key = routine_tombstone_key(rt.destination)
            self.store.add_tombstone(rt.scope, key, expires, reason)
            self.store.audit(now, rt.scope, "forget.routine", {"ids": removed, "tombstone": key})
            return {"forgot": "routine", "id": rt.id, "removed": removed, "description": rt.describe(),
                    "tombstone_until": iso(expires)}
        f = self.store.get_fact(record_id)
        if f:
            self.store.delete_fact(f.id)
            sig = f"{f.category}/{f.key}"
            self.store.add_tombstone(f.scope, sig, expires, reason)  # an explicit re-statement lifts it
            self.store.audit(now, f.scope, "forget.fact", {"id": f.id, "fact": f.describe()})
            return {"forgot": "fact", "id": f.id, "description": f.describe(), "tombstone_until": iso(expires)}
        return {"forgot": None}

    def fact_tombstoned(self, scope: str, category: str, key: str, now: datetime) -> bool:
        return self.store.is_tombstoned(scope, f"{category}/{key}", now)

    def _record(self, prop: Proposal):
        return self.store.get_routine(prop.record_id) if prop.kind == "routine" else self.store.get_fact(prop.record_id)

    # ------------------------------------------------------------------
    # maintenance
    # ------------------------------------------------------------------
    def maintenance(self, now: datetime) -> dict[str, int]:
        cut = now - timedelta(days=self.policy.episodic_retention_days)
        removed = self.store.delete_episodes_before(cut)
        expired = self.store.purge_expired_tombstones(now)
        retired = 0
        for r in self.store.routines(None, [State.CANDIDATE, State.CONFIRMED, State.OBSERVED]):
            before = r.state
            self.evaluate_routine(r, now)
            retired += int(before != State.RETIRED and r.state == State.RETIRED)
        return {"episodes_removed": removed, "tombstones_expired": expired, "routines_retired": retired}


# ----------------------------------------------------------------------
def _count_weekday(start: datetime, end: datetime, dow: int) -> int:
    """Number of calendar days with weekday `dow` in [start.date(), end.date()]."""
    if end < start:
        return 0
    first = start.date()
    days_ahead = (dow - first.weekday()) % 7
    first_hit = first + timedelta(days=days_ahead)
    if first_hit > end.date():
        return 0
    return (end.date() - first_hit).days // 7 + 1


def _cond_str(conds: dict[str, str]) -> str:
    return ";".join(f"{k}={v}" for k, v in sorted(conds.items()))


def _cond_get(cond: Optional[str], name: str) -> Optional[str]:
    if not cond:
        return None
    for part in cond.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            if k == name:
                return v
    return None


def _cond_matches(cond: str, now_conds: dict[str, str]) -> bool:
    for part in cond.split(";"):
        if "=" in part:
            k, v = part.split("=", 1)
            if now_conds.get(k) != v:
                return False
    return True


def _norm_value(v: Any) -> Any:
    if isinstance(v, float) and v.is_integer():
        return int(v)
    if isinstance(v, str):
        return v.strip().lower()
    return v


def _denorm(norm: Any, evs: list[Evidence]) -> Any:
    for e in evs:
        if _norm_value(e.value) == norm:
            return e.value
    return norm
