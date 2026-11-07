"""Build benchmark scenarios from real trips, following docs/DATASET_USECASES.md.

Two kinds of scenario:

* ``solo`` - one real VED car, one driver, its whole logged year, nothing added.
  Covers the routine use cases (U1-U7, U29); ground truth is what the car did later.
* ``household`` - two real cars composed into one shared car (U8-U10, U20), with
  synthetic overlays placed at real trip times: utterances and traps (U11-U14, U16, U23,
  U33, U35) and settings conditioned on the real outside temperature (U15, U19, U22, U25).

Every event carries ``provenance`` (real | composed | real_context | synthetic) and the
use cases it serves.
"""

from __future__ import annotations

import random
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from carmem.consolidation import season_of
from carmem.models import Trip

from .places import CarProfile, PlaceTrip

# Share of a primary driver's routine trips that the other household driver makes instead,
# measured from NHTS 2022 by bench/real/nhts.py (data/nhts/sharing_2022.json). The 0.10
# fallback is used only when that file is missing.
from .nhts import load_swap_rate  # noqa: E402

_NHTS = load_swap_rate()
DEFAULT_SWAP_RATE, SWAP_RATE_SOURCE = _NHTS if _NHTS else (0.10, "assumption (data/nhts/sharing_2022.json missing)")

SOLO_MIN_TRIPS = 150
SOLO_MIN_WEEKS = 20


@dataclass
class RealEvent:
    ts: datetime
    type: str  # trip | say | sensor
    driver: str  # person id, "guest", or "vehicle"
    provenance: str
    use_cases: list[str] = field(default_factory=list)
    trip: Trip | None = None
    text: str = ""
    payload: dict[str, Any] = field(default_factory=dict)
    reject_if_proposed: list[str] = field(default_factory=list)
    forget_if_proposed: list[str] = field(default_factory=list)
    src: str = ""  # e.g. "ved:531/1234"
    actual_driver: str = ""  # for swapped trips: whose real trip this was
    oat: float | None = None
    moving_probe: bool = False  # U24: ask the car mid-trip whether it would act on seat/climate


@dataclass
class Scenario:
    name: str
    kind: str  # solo | household
    persons: dict[str, dict[str, Any]]
    events: list[RealEvent]
    use_cases: list[str]
    gt: dict[str, Any] = field(default_factory=dict)
    meta: dict[str, Any] = field(default_factory=dict)


def _trip(pt: PlaceTrip, who: str, settings: dict[str, Any] | None = None, origin: str | None = None,
          dest: str | None = None) -> Trip:
    return Trip(pt.start, max(pt.end, pt.start + timedelta(minutes=1)), origin or pt.origin_name,
                dest or pt.dest_name, [who], dict(settings or {}), {"tolls": False}, distance_km=pt.raw.km)


def solo_candidates(profiles: dict[int, CarProfile]) -> list[int]:
    return sorted(v for v, p in profiles.items() if p.trips >= SOLO_MIN_TRIPS and p.active_weeks >= SOLO_MIN_WEEKS)


def build_solo(veh: int, trips: list[PlaceTrip], prof: CarProfile) -> Scenario:
    pid = f"d{veh}"
    evs = [RealEvent(pt.start, "trip", pid, "real", ["U1", "U2", "U3", "U4", "U5", "U6", "U7", "U29"],
                     trip=_trip(pt, pid), src=f"ved:{veh}/{pt.raw.trip}", oat=pt.raw.oat) for pt in trips]
    return Scenario(
        name=f"solo_ved{veh}", kind="solo",
        persons={pid: {"name": f"Driver {veh}", "signals": [("key_fob", f"fob-{veh}")]}},
        events=evs, use_cases=["U1", "U2", "U3", "U4", "U5", "U6", "U7", "U29"],
        meta={"vehicles": [veh], "home": prof.home, "work": prof.work, "trips": prof.trips,
              "active_weeks": prof.active_weeks, "first": prof.first.isoformat(), "last": prof.last.isoformat()},
    )


# ---------------------------------------------------------------------------
# households
# ---------------------------------------------------------------------------

def pick_pairs(profiles: dict[int, CarProfile], n: int, min_overlap_weeks: int = 20) -> list[tuple[int, int]]:
    """Pair well-covered cars that both have a detectable home and overlap in time."""
    cands = sorted((p for p in profiles.values()
                    if p.home and p.trips >= SOLO_MIN_TRIPS and p.active_weeks >= SOLO_MIN_WEEKS),
                   key=lambda p: (-p.trips, p.veh))
    used: set[int] = set()
    pairs = []
    for a in cands:
        if a.veh in used:
            continue
        for b in cands:
            if b.veh == a.veh or b.veh in used:
                continue
            overlap = (min(a.last, b.last) - max(a.first, b.first)).days / 7
            if overlap >= min_overlap_weeks:
                pairs.append((a.veh, b.veh))
                used |= {a.veh, b.veh}
                break
        if len(pairs) >= n:
            break
    return pairs


def _top_weekday_pair(trips: list[PlaceTrip], exclude_dest: set[str] = frozenset()) -> tuple[str, str] | None:
    c = Counter((t.origin_name, t.dest_name) for t in trips
                if t.start.weekday() < 5 and t.origin_name != t.dest_name and t.dest_name not in exclude_dest)
    return c.most_common(1)[0][0] if c else None


def _cabin_temp(person: str, oat: float | None, when: datetime) -> float:
    """Synthetic set-point driven by the real outside temperature (season if not logged)."""
    cold = (oat < 12.0) if oat is not None else season_of(when) in ("winter", "autumn")
    if person == "a":
        return 21.0 if cold else 19.0
    return 23.0


def _settings(person: str, pt: PlaceTrip) -> dict[str, Any]:
    oat = pt.raw.oat
    s: dict[str, Any] = {"cabin_temp": _cabin_temp(person, oat, pt.start)}
    s["seat_position"] = "3" if person == "a" else "7"
    if person == "a":
        s["media"] = "news" if pt.start.weekday() < 5 and pt.start.hour < 11 else "jazz"
        if (oat is not None and oat < 0) or (oat is None and season_of(pt.start) == "winter"):
            s["fan"] = "high"
    else:
        s["media"] = "podcast"
    return s


def build_household(a_veh: int, b_veh: int, trips: dict[int, list[PlaceTrip]], profiles: dict[int, CarProfile],
                    guest_source: list[PlaceTrip], seed: int = 7, swap_rate: float = DEFAULT_SWAP_RATE) -> Scenario:
    rng = random.Random(seed * 1000 + a_veh)
    pa, pb = profiles[a_veh], profiles[b_veh]
    lo, hi = max(pa.first, pb.first), min(pa.last, pb.last)
    ta = [t for t in trips[a_veh] if lo <= t.start <= hi]
    tb = [t for t in trips[b_veh] if lo <= t.start <= hi]

    # shared home: B lives where A lives
    def b_name(p: str) -> str:
        return "home" if p == "home" else ("work_b" if p == "work" else p)

    # a car cannot be in two places: drop B trips that overlap A trips
    a_spans = [(t.start - timedelta(minutes=10), t.end + timedelta(minutes=10)) for t in ta]
    a_spans.sort()
    kept_b, dropped = [], 0
    j = 0
    for t in tb:
        while j < len(a_spans) and a_spans[j][1] < t.start:
            j += 1
        if j < len(a_spans) and a_spans[j][0] <= t.end:
            dropped += 1
            continue
        kept_b.append(t)

    # U9: B occasionally does A's top weekday routine
    a_top = _top_weekday_pair(ta)
    swapped_ids: set[int] = set()
    if a_top:
        for t in ta:
            if (t.origin_name, t.dest_name) == a_top and t.start.weekday() < 5 and rng.random() < swap_rate:
                swapped_ids.add(t.raw.trip)

    evs: list[RealEvent] = []
    for t in ta:
        who = "b" if t.raw.trip in swapped_ids else "a"
        settings = _settings(who, t)
        prov = "composed" if who == "b" else "real"
        ucs = ["U8", "U15", "U19", "U20", "U22", "U25"] + (["U9"] if who == "b" else [])
        evs.append(RealEvent(t.start, "trip", who, prov, ucs, trip=_trip(t, who, settings),
                             src=f"ved:{a_veh}/{t.raw.trip}", actual_driver="a", oat=t.raw.oat,
                             moving_probe=t.raw.km > 2 and rng.random() < 0.1))
    for t in kept_b:
        settings = _settings("b", t)
        evs.append(RealEvent(t.start, "trip", "b", "composed", ["U8", "U15", "U20", "U22", "U25"],
                             trip=_trip(t, "b", settings, origin=b_name(t.origin_name), dest=b_name(t.dest_name)),
                             src=f"ved:{b_veh}/{t.raw.trip}", actual_driver="b", oat=t.raw.oat))
    evs.sort(key=lambda e: e.ts)

    a_trips = [e for e in evs if e.type == "trip" and e.driver == "a"]
    b_trips = [e for e in evs if e.type == "trip" and e.driver == "b"]

    def after(es: list[RealEvent], frac: float) -> RealEvent:
        return es[min(len(es) - 1, int(len(es) * frac))]

    def say(anchor: RealEvent, who: str, text: str, ucs: list[str], minutes: int = 2) -> None:
        evs.append(RealEvent(anchor.ts + timedelta(minutes=minutes), "say", who, "synthetic", ucs, text=text))

    # U14 / U10 / U35: stated facts by A, early in the year
    say(after(a_trips, 0.02), "a", "my kid's school is Oakwood Primary", ["U14", "U10"])
    say(after(a_trips, 0.04), "a", "I'm allergic to penicillin", ["U14", "U10"])
    say(after(a_trips, 0.06), "a", "I hate toll roads", ["U14"])
    say(after(a_trips, 0.08), "a", "remember that there is a child seat in the back", ["U35"])
    # U16: vehicle fault, sensor plus both people mention it
    s16 = after(a_trips, 0.30)
    evs.append(RealEvent(s16.ts - timedelta(hours=2), "sensor", "vehicle", "synthetic", ["U16"],
                         payload={"key": "left_rear_sensor", "value": "intermittent fault code B1234"}))
    say(s16, "a", "the left rear sensor is flaky", ["U16"])
    say(after(b_trips, 0.35), "b", "the left rear sensor keeps beeping at me", ["U16"])
    # U33: tyre pressure warning, both mention it
    say(after(a_trips, 0.50), "a", "the tyre pressure warning came on", ["U33"])
    say(after(b_trips, 0.52), "b", "tyre pressure light is on again", ["U33"])

    # U11 / U23: a guest (valet) drives a real route from another car, voice only
    g = guest_source[rng.randrange(len(guest_source))]
    gts = after(a_trips, 0.45).ts.replace(hour=20, minute=15) + timedelta(days=1)
    evs.append(RealEvent(gts, "say", "guest", "synthetic", ["U11"], text="my home is 12 Elm Street"))
    evs.append(RealEvent(gts + timedelta(minutes=1), "say", "guest", "synthetic", ["U11"], text="set it to 25 degrees"))
    gt_trip = Trip(gts + timedelta(minutes=2), gts + timedelta(minutes=2) + (g.end - g.start), "home", "12 Elm Street",
                   [], {"cabin_temp": 25.0, "seat_position": "1"}, {"tolls": False}, distance_km=g.raw.km)
    evs.append(RealEvent(gt_trip.start, "trip", "guest", "synthetic", ["U11", "U23"], trip=gt_trip,
                         src=f"ved:{g.veh}/{g.raw.trip} (route only)"))

    # U12: B says "no" to their top routine for two proposals mid-year; U13: A forgets a routine
    b_top = _top_weekday_pair([t for t in kept_b], exclude_dest={"home"})
    corrected = forgotten = None
    if b_top:
        corrected = b_name(b_top[1])
        start = after(b_trips, 0.5).ts
        for e in b_trips:
            if e.ts >= start and e.ts < start + timedelta(weeks=3):
                e.reject_if_proposed = [corrected]
                e.use_cases.append("U12")
    a_second = _top_weekday_pair(ta, exclude_dest={"home", a_top[1] if a_top else ""})
    if a_second:
        forgotten = a_second[1]
        start = after(a_trips, 0.6).ts
        for e in a_trips:
            if e.ts >= start and e.ts < start + timedelta(weeks=2):
                e.forget_if_proposed = [forgotten]
                e.use_cases.append("U13")
    evs.sort(key=lambda e: e.ts)

    gt = {
        "expected_facts": {
            "a": [("school", "school_location", "Oakwood"), ("route_prefs", "avoid_tolls", "True"),
                  ("climate", "cabin_temp", "21"), ("climate", "cabin_temp", "19"),
                  ("health", "allergy", "penicillin"), ("seat", "seat_position", "3"), ("media", "media", "")],
            "b": [("climate", "cabin_temp", "23"), ("seat", "seat_position", "7"), ("media", "media", "podcast")],
            "vehicle": [("vehicle_fault", "left_rear_sensor", ""), ("vehicle_fault", "tyre_pressure", "")],
        },
        "forbidden_values": {"a": ["Elm", "podcast"], "b": ["Oakwood", "penicillin", "Elm", "child seat", "jazz"]},
        "guest_strings": ["Elm"],
        "corrected": {"b": corrected} if corrected else {},
        "forgotten": {"a": forgotten} if forgotten else {},
        "a_top": list(a_top) if a_top else None,
        "settings_by_person": {"a": {"seat_position": "3"}, "b": {"seat_position": "7", "cabin_temp": 23.0}},
        "cloud_sensitive": ["Oakwood", "penicillin", "child seat"],
        "qa": [
            {"who": "a", "q": "where is my kid's school", "expect": ["Oakwood"], "forbid": [], "uc": "U14"},
            {"who": "a", "q": "what am I allergic to", "expect": ["penicillin"], "forbid": [], "uc": "U14"},
            {"who": "a", "q": "do I like toll roads", "expect": ["avoid_tolls = True"], "forbid": [], "uc": "U14"},
            {"who": "a", "q": "what is wrong with the car", "expect": ["left rear sensor"], "forbid": [], "uc": "U16"},
            {"who": "b", "q": "where is my kid's school", "expect": [], "forbid": ["Oakwood"], "uc": "U10"},
            {"who": "b", "q": "what am I allergic to", "expect": [], "forbid": ["penicillin"], "uc": "U10"},
            {"who": "b", "q": "is there a child seat", "expect": [], "forbid": ["child seat"], "uc": "U10"},
            {"who": "b", "q": "what temperature do I like", "expect": ["cabin_temp = 23"],
             "forbid": ["cabin_temp = 21", "cabin_temp = 19"], "uc": "U20"},
            {"who": "b", "q": "what is wrong with the car", "expect": ["left rear sensor"], "forbid": [], "uc": "U16"},
        ],
    }
    ucs = sorted({u for e in evs for u in e.use_cases}, key=lambda u: int(u[1:]))
    return Scenario(
        name=f"household_ved{a_veh}_ved{b_veh}", kind="household",
        persons={"a": {"name": "Asha", "signals": [("key_fob", f"fob-{a_veh}"), ("voice", f"v-{a_veh}")]},
                 "b": {"name": "Ben", "signals": [("phone", f"phone-{b_veh}"), ("voice", f"v-{b_veh}")]}},
        events=evs, use_cases=ucs, gt=gt,
        meta={"vehicles": [a_veh, b_veh], "window": [lo.isoformat(), hi.isoformat()],
              "b_trips_dropped_for_overlap": dropped, "swapped_trips": len(swapped_ids), "swap_rate": swap_rate,
              "swap_rate_source": SWAP_RATE_SOURCE if swap_rate == DEFAULT_SWAP_RATE else "set on the command line",
              "composition": "B's trips are another real car's, re-homed to A's home; place names are neutral ids"},
    )


def plant_traps(sc: Scenario, log: dict[str, Any]) -> None:
    """Re-plant U12 ("no") and U13 ("forget that") on routines carmem actually proposed in a
    dry run, so the traps fire on real proposals instead of on routines that never confirm."""
    if sc.kind != "household":
        return
    trips = [e for e in sc.events if e.type == "trip"]
    if not trips:
        return
    mid = trips[len(trips) * 2 // 5].ts
    props = [p for p in log["proposals"] if datetime.fromisoformat(p["ts"]) >= mid]

    def choose(driver: str, exclude: set[str]) -> tuple[str, datetime] | None:
        c = Counter(p["dest"] for p in props if p["driver"] == driver and p["dest"] not in exclude)
        ranked = sorted(c.items(), key=lambda kv: (kv[0] == "home", -kv[1], kv[0]))
        for dest, n in ranked:
            if n >= 2:
                first = min(datetime.fromisoformat(p["ts"]) for p in props
                            if p["driver"] == driver and p["dest"] == dest)
                return dest, first
        return None

    b = choose("b", set())
    a = choose("a", {b[0]} if b else set())
    if b:
        for e in sc.events:
            if e.type == "trip" and e.driver == "b":
                e.reject_if_proposed = []
                if "U12" in e.use_cases:
                    e.use_cases.remove("U12")
                if b[1] <= e.ts < b[1] + timedelta(weeks=3):
                    e.reject_if_proposed = [b[0]]
                    e.use_cases.append("U12")
        sc.gt["corrected"] = {"b": b[0]}
    if a:
        for e in sc.events:
            if e.type == "trip" and e.driver == "a":
                e.forget_if_proposed = []
                if "U13" in e.use_cases:
                    e.use_cases.remove("U13")
                if a[1] <= e.ts < a[1] + timedelta(weeks=2):
                    e.forget_if_proposed = [a[0]]
                    e.use_cases.append("U13")
        sc.gt["forgotten"] = {"a": a[0]}
    sc.meta["traps_planted_on"] = {"U12": b[0] if b else None, "U13": a[0] if a else None,
                                   "method": "dry run: most-proposed routine after 40% of the window"}
