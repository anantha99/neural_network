"""Replay real-data scenarios through CarMemory and score them per use case.

    python -m bench.real.run_real                      # all scenarios, default radius
    python -m bench.real.run_real --radius 1000 --households 6 --out bench/results/real.json
    python -m bench.real.run_real --export fixtures/real --report docs/REAL_BENCH_RESULTS.md

Routine ground truth is held-out: every 28 days the confirmed routines are snapshotted
and checked against what the real car did over the next 56 days. Proposals are scored
against the trip the driver actually took next.
"""

from __future__ import annotations

import argparse
import json
import statistics as st
import sys
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

from carmem.consolidation import norm_place
from carmem.memory import CarMemory
from carmem.models import IdentitySignal, State, Tier, is_guest
from carmem.policy import Policy

from . import oracle
from .places import load_raw, resolve
from .scenarios import (DEFAULT_SWAP_RATE, RealEvent, Scenario, build_household, build_solo, pick_pairs,
                        plant_traps, solo_candidates)

CHECKPOINT_DAYS = 28
HELDOUT_DAYS = 56
HELDOUT_TRUE_RATIO = 0.5
GAP_DAYS = 14

# US federal holidays in the VED window; school winter break is approximate (Ann Arbor area).
HOLIDAYS = {date(2017, 11, 23), date(2017, 11, 24), date(2017, 12, 25), date(2018, 1, 1), date(2018, 1, 15),
            date(2018, 5, 28), date(2018, 7, 4), date(2018, 9, 3)}
WINTER_BREAK = (date(2017, 12, 22), date(2018, 1, 2))

GUEST_SIGNALS = [IdentitySignal("voice", "v-unknown", 0.55)]


def is_break(d: date) -> bool:
    return d in HOLIDAYS or WINTER_BREAK[0] <= d <= WINTER_BREAK[1]


# ---------------------------------------------------------------------------
# replay
# ---------------------------------------------------------------------------

def replay(mem: CarMemory, sc: Scenario, policy: Policy) -> dict[str, Any]:
    signals = {pid: [IdentitySignal(k, v) for k, v in info["signals"]] for pid, info in sc.persons.items()}
    log: dict[str, Any] = {"proposals": [], "fact_proposals": [], "no_said": defaultdict(int), "forget_done": {},
                           "snapshots": [], "moving_probes": [], "guest_scopes": set()}
    first = sc.events[0].ts
    next_cp = first + timedelta(days=CHECKPOINT_DAYS * 2)
    cur: tuple[str, datetime] | None = None  # (driver, time the session was last used)

    def session(driver: str, ts: datetime) -> None:
        nonlocal cur
        if cur and cur[0] == driver and ts - cur[1] <= timedelta(minutes=30):
            cur = (driver, max(cur[1], ts))
            return
        if cur:
            mem.ignition_off(now=max(cur[1] + timedelta(minutes=1), ts - timedelta(minutes=3)))
        occ = mem.ignition_on(GUEST_SIGNALS if driver == "guest" else signals[driver], now=ts - timedelta(minutes=2))
        if occ.is_guest:
            log["guest_scopes"].add(occ.driver_id)
        cur = (driver, ts)

    for ev in sc.events:
        while ev.ts >= next_cp:
            log["snapshots"].append({"ts": next_cp.isoformat(), "routines": [
                {"scope": r.scope, "dow": r.dow, "minute": r.minute_of_day, "dest": r.destination, "origin": r.origin,
                 "id": r.id} for r in mem.store.routines(None, [State.CONFIRMED])]})
            next_cp += timedelta(days=CHECKPOINT_DAYS)
        if ev.type == "sensor":
            mem.record_vehicle_event(ev.payload["key"], ev.payload["value"], now=ev.ts)
            continue
        session(ev.driver, ev.ts)
        if ev.type == "say":
            mem.hear(ev.text, now=ev.ts)
            continue
        t = ev.trip
        scope = mem.occupancy.driver_id
        props = mem.proactive(now=t.start - timedelta(minutes=1), location=t.origin)
        for p in props:
            if p.kind == "routine":
                dest = p.action.get("destination", "")
                rt = mem.store.get_routine(p.record_id)
                log["proposals"].append({
                    "ts": t.start.isoformat(), "scope": p.scope, "driver": ev.driver, "dest": dest,
                    "actual": t.destination, "origin": t.origin, "tier": p.tier.value,
                    "correct": norm_place(dest) == norm_place(t.destination),
                    "minute_err": (t.start.hour * 60 + t.start.minute - rt.minute_of_day) if rt else None,
                    "provenance": ev.provenance, "src": ev.src,
                })
                if any(norm_place(dest) == norm_place(x) for x in ev.reject_if_proposed):
                    mem.hear("no", now=t.start)
                    log["no_said"][dest] += 1
                    log.setdefault("no_times", []).append(t.start.isoformat())
                if any(norm_place(x) == norm_place(dest) for x in ev.forget_if_proposed) and dest not in log["forget_done"]:
                    r = mem.hear("forget that", now=t.start)
                    log["forget_done"][dest] = {"ts": t.start.isoformat(), "via": "proposal", "result": r.get("forget")}
            else:
                key, val = p.action.get("key"), p.action.get("value")
                log["fact_proposals"].append({
                    "ts": t.start.isoformat(), "scope": p.scope, "driver": ev.driver, "key": key, "value": val,
                    "tier": p.tier.value, "actual": t.settings.get(key),
                    "correct": key in t.settings and str(t.settings.get(key)) == str(val),
                })
        if ev.forget_if_proposed and not any(norm_place(x) == norm_place(k) for x in ev.forget_if_proposed
                                             for k in log["forget_done"]):
            target = ev.forget_if_proposed[0]
            r = mem.hear(f"forget that I go to {target}", now=t.start)
            log["forget_done"][target] = {"ts": t.start.isoformat(), "via": "search", "result": r.get("forget")}
        if ev.moving_probe and not is_guest(scope):
            mid = t.start + (t.end - t.start) / 2
            acts = [p for p in mem.consolidator.proposals(scope, mid, set(), None)
                    if p.kind == "fact" and p.tier == Tier.ACT and p.action.get("key") in ("seat_position", "cabin_temp",
                                                                                         "fan", "media")]
            log["moving_probes"].append({"ts": mid.isoformat(), "acts": [p.action.get("key") for p in acts]})
        mem.record_trip(t, now=t.end)
        cur = (cur[0], t.end) if cur else cur
    if cur:
        mem.ignition_off(now=cur[1] + timedelta(minutes=5))
    return log


# ---------------------------------------------------------------------------
# scoring helpers
# ---------------------------------------------------------------------------

def _trips_by_driver(sc: Scenario) -> dict[str, list[RealEvent]]:
    out: dict[str, list[RealEvent]] = defaultdict(list)
    for e in sc.events:
        if e.type == "trip" and e.driver != "guest":
            out[e.driver].append(e)
    return out


def _matches(e: RealEvent, dow: int, minute: int, dest: str, tol: int) -> bool:
    t = e.trip
    return (t.start.weekday() == dow and abs(t.start.hour * 60 + t.start.minute - minute) <= tol
            and norm_place(t.destination) == norm_place(dest))


def heldout(sc: Scenario, log: dict[str, Any], policy: Policy, scope_of: dict[str, str]) -> dict[str, Any]:
    """Each routine confirmed at a checkpoint either comes true in the next 56 days or not."""
    tol = policy.routine_slot_tolerance_min
    trips = _trips_by_driver(sc)
    driver_of = {v: k for k, v in scope_of.items()}
    rows = []
    for snap in log["snapshots"]:
        t0 = datetime.fromisoformat(snap["ts"])
        t1 = t0 + timedelta(days=HELDOUT_DAYS)
        for r in snap["routines"]:
            drv = driver_of.get(r["scope"])
            if drv is None:
                continue
            window = [e for e in trips[drv] if t0 <= e.ts < t1]
            active = {e.ts.date() for e in window if e.ts.weekday() == r["dow"]}
            if len(active) < 2:
                continue  # the car was not driven on that weekday often enough to judge
            hit = {e.ts.date() for e in window if _matches(e, r["dow"], r["minute"], r["dest"], tol)}
            rows.append({"ts": snap["ts"], "scope": r["scope"], "dest": r["dest"], "origin": r["origin"],
                         "dow": r["dow"], "eligible": len(active), "hits": len(hit),
                         "true": len(hit) / len(active) >= HELDOUT_TRUE_RATIO})
    n = len(rows)
    kinds: dict[str, list[dict]] = defaultdict(list)
    for r in rows:
        kind = ("commute" if norm_place(r["origin"]) == "home" and norm_place(r["dest"]).startswith("work")
                else "to_home" if norm_place(r["dest"]) == "home" else "other")
        kinds[kind].append(r)
    return {
        "evaluated": n, "came_true": sum(r["true"] for r in rows),
        "precision": round(sum(r["true"] for r in rows) / n, 3) if n else None,
        "mean_hit_ratio": round(st.mean(r["hits"] / r["eligible"] for r in rows), 3) if n else None,
        "by_kind": {k: {"evaluated": len(v), "precision": round(sum(r["true"] for r in v) / len(v), 3)}
                    for k, v in kinds.items()},
        "rows": rows,
    }


def _rate(xs: list[dict], key: str = "correct") -> dict[str, Any]:
    return {"n": len(xs), "precision": round(sum(x[key] for x in xs) / len(xs), 3) if xs else None}


def score_routines(sc: Scenario, log: dict[str, Any], policy: Policy, mem: CarMemory,
                   drivers: list[str]) -> dict[str, Any]:
    scope_of = {d: d for d in drivers}
    m: dict[str, Any] = {}
    props = [p for p in log["proposals"] if p["driver"] in drivers]
    trips = _trips_by_driver(sc)
    n_trips = sum(len(trips[d]) for d in drivers)
    m["trips"] = n_trips
    m["proposals"] = _rate(props)
    m["proposals_by_tier"] = {t: _rate([p for p in props if p["tier"] == t]) for t in sorted({p["tier"] for p in props})}
    m["coverage"] = round(len({p["ts"] for p in props if p["correct"]}) / n_trips, 3) if n_trips else 0.0
    ho = heldout(sc, log, policy, scope_of)
    m["heldout"] = {k: v for k, v in ho.items() if k != "rows"}

    # U1 commute
    first = sc.events[0].ts
    commute = [p for p in props if p["correct"] and norm_place(p["origin"]) == "home"
               and norm_place(p["dest"]).startswith("work")]
    m["U1"] = {
        "commute_confirmed": any(r["origin"] == "home" and r["dest"].startswith("work")
                                 for s in log["snapshots"] for r in s["routines"] if r["scope"] in drivers),
        "days_to_first_correct_commute": (datetime.fromisoformat(commute[0]["ts"]) - first).days if commute else None,
        "heldout": ho["by_kind"].get("commute"),
    }
    # U2 chained legs: previous trip by the same driver ended <= 15 min earlier, before 10:00
    chain_ts = set()
    for d in drivers:
        ts = trips[d]
        for a, b in zip(ts, ts[1:]):
            if (b.ts.date() == a.trip.end.date() and b.ts.hour < 10
                    and timedelta(0) <= b.ts - a.trip.end <= timedelta(minutes=15)):
                chain_ts.add(b.ts.isoformat())
    m["U2"] = {"chained_legs": len(chain_ts), "proposals": _rate([p for p in props if p["ts"] in chain_ts])}
    # U3 weekly, non-home, non-work routines
    m["U3"] = {"heldout": ho["by_kind"].get("other")}
    # U4 irregular places: >= 4 visits, never twice in the same weekday/45-min slot
    irregular = set()
    for d in drivers:
        visits: dict[str, list[datetime]] = defaultdict(list)
        for e in trips[d]:
            visits[e.trip.destination].append(e.ts)
        for place, tss in visits.items():
            if len(tss) < 4 or place == "home":
                continue
            slots = Counter((t.weekday(), (t.hour * 60 + t.minute) // policy.routine_slot_tolerance_min) for t in tss)
            if max(slots.values()) <= 1:
                irregular.add((d, place))
    promoted_irregular = {(r["scope"], r["dest"]) for s in log["snapshots"] for r in s["routines"]} & irregular
    m["U4"] = {"irregular_places": len(irregular), "promoted": len(promoted_irregular)}
    # U5 routines that stop in the real data
    tol = policy.routine_slot_tolerance_min
    stopped = []
    seen = set()
    for s in log["snapshots"]:
        for r in s["routines"]:
            key = (r["scope"], r["dow"], r["dest"])
            if r["scope"] not in drivers or key in seen:
                continue
            seen.add(key)
            ts = trips[r["scope"]]
            matches = [e.ts for e in ts if _matches(e, r["dow"], r["minute"], r["dest"], tol)]
            if not matches:
                continue
            last = max(matches)
            later = [e for e in ts if e.ts > last + timedelta(days=28)]
            if not later:
                continue  # the car itself stops being logged; cannot tell
            wrong_after = [p for p in props if p["scope"] == r["scope"] and p["dest"] == r["dest"]
                           and datetime.fromisoformat(p["ts"]).weekday() == r["dow"]
                           and datetime.fromisoformat(p["ts"]) > last]
            stopped.append({"dest": r["dest"], "dow": r["dow"], "stopped": last.date().isoformat(),
                            "proposals_after_stop": len(wrong_after),
                            "days_proposed_after_stop": max(((datetime.fromisoformat(p["ts"]) - last).days
                                                             for p in wrong_after), default=0)})
    m["U5"] = {"stopped_routines": len(stopped),
               "proposals_after_stop": sum(x["proposals_after_stop"] for x in stopped),
               "max_days_proposed_after_stop": max((x["days_proposed_after_stop"] for x in stopped), default=0),
               "detail": stopped[:10]}
    # U6 holidays and winter break vs other days
    hol = [p for p in props if is_break(datetime.fromisoformat(p["ts"]).date())]
    other = [p for p in props if not is_break(datetime.fromisoformat(p["ts"]).date())]
    m["U6"] = {"break_days": _rate(hol), "other_days": _rate(other)}
    # U7 return after >= 14 days without trips
    after_gap = []
    gaps = 0
    for d in drivers:
        ts = trips[d]
        for a, b in zip(ts, ts[1:]):
            if b.ts - a.ts >= timedelta(days=GAP_DAYS):
                gaps += 1
                after_gap += [p for p in props if p["driver"] == d
                              and b.ts <= datetime.fromisoformat(p["ts"]) < b.ts + timedelta(days=14)]
    m["U7"] = {"gaps": gaps, "proposals_in_14d_after": _rate(after_gap)}
    # U29 timing of correct proposals
    errs = [abs(p["minute_err"]) for p in props if p["correct"] and p["minute_err"] is not None]
    m["U29"] = {"correct_proposals": len(errs), "median_abs_minutes": st.median(errs) if errs else None,
                "p90_abs_minutes": sorted(errs)[int(len(errs) * 0.9)] if errs else None}
    return m


def score_household(sc: Scenario, log: dict[str, Any], policy: Policy, mem: CarMemory) -> dict[str, Any]:
    gt = sc.gt
    m = score_routines(sc, log, policy, mem, ["a", "b"])
    trips = _trips_by_driver(sc)
    # U8 contamination: a routine in one scope whose destination only the other person drives to
    dests = {d: {norm_place(e.trip.destination) for e in trips[d]} for d in ("a", "b")}
    leaks = []
    for scope, other in (("a", "b"), ("b", "a")):
        for r in mem.store.routines([scope]):
            if norm_place(r.destination) in dests[other] - dests[scope]:
                leaks.append(f"{scope}: routine to {r.destination} ({r.state.value}) only {other} drives there")
        blob = (" ".join(f.describe() for f in mem.store.facts([scope])) + " " +
                " ".join(e.text for e, _ in mem.store.episodes([scope], limit=100000))).lower()
        for s in gt["forbidden_values"].get(scope, []):
            if s.lower() in blob:
                leaks.append(f"{scope}: contains '{s}'")
    m["U8"] = {"leaks": leaks}
    # U9 swapped trips: B must not end up with A's routine, A's support must not include B's trips
    a_top = gt.get("a_top")
    b_has = bool(a_top) and any(r.origin == a_top[0] and r.destination == a_top[1] and r.state == State.CONFIRMED
                                for r in mem.store.routines(["b"]))
    m["U9"] = {"swapped_trips": sc.meta["swapped_trips"], "b_confirmed_a_routine": b_has}
    # U10 / U14 / U16 / U20 QA on the rendered context
    end = sc.events[-1].ts + timedelta(days=1)
    signals = {pid: [IdentitySignal(k, v) for k, v in info["signals"]] for pid, info in sc.persons.items()}
    qa = []
    for q in gt["qa"]:
        mem.ignition_on(signals[q["who"]], now=end)
        text = mem.context(q["q"], "local", now=end).render().lower()
        ok = all(x.lower() in text for x in q["expect"]) and not any(x.lower() in text for x in q["forbid"])
        qa.append({"uc": q["uc"], "who": q["who"], "q": q["q"], "ok": ok})
        mem.ignition_off(now=end + timedelta(minutes=30))
    m["qa"] = qa
    m["qa_accuracy"] = round(sum(r["ok"] for r in qa) / len(qa), 3)
    # expected facts
    missing = []
    for scope, items in gt["expected_facts"].items():
        facts = mem.store.facts([scope], states=[State.CONFIRMED])
        for cat, key, sub in items:
            if not any(f.category == cat and f.key == key and sub.lower() in str(f.value).lower() for f in facts):
                missing.append(f"{scope}:{cat}/{key}~{sub}")
    m["missing_expected_facts"] = missing
    # setting proposals (U15, U19, U20, U22, U25)
    fps = log["fact_proposals"]
    m["settings"] = {k: _rate([p for p in fps if p["key"] == k]) for k in sorted({p["key"] for p in fps})}
    other_val = {"a": gt["settings_by_person"]["b"], "b": gt["settings_by_person"]["a"]}
    m["U20"] = {"wrong_person_values": sum(1 for p in fps if p["driver"] in other_val
                                           and str(other_val[p["driver"]].get(p["key"], object())) == str(p["value"]))}
    # U11 / U23 guest
    present = mem.store.scopes_present()
    everything = (" ".join(f.describe() for f in mem.store.facts(None)) + " " +
                  " ".join(e.text for e, _ in mem.store.episodes(list(present), limit=100000))).lower()
    seat_a = [str(f.value) for f in mem.store.facts(["a"], "seat", states=[State.CONFIRMED])]
    m["U11"] = {"guest_scopes_remaining": [s for s in present if is_guest(s)],
                "guest_strings_remaining": [s for s in gt["guest_strings"] if s.lower() in everything]}
    m["U23"] = {"a_seat_after_guest": seat_a, "ok": "1" not in seat_a and "3" in seat_a}
    # U12 correction
    if gt["corrected"]:
        dest = gt["corrected"]["b"]
        no_times = sorted(log.get("no_times", []))
        second = datetime.fromisoformat(no_times[1]) if len(no_times) >= 2 else None
        after = [p for p in log["proposals"] if p["scope"] == "b" and p["dest"] == dest and second
                 and datetime.fromisoformat(p["ts"]) > second]
        m["U12"] = {"target": dest, "no_said": log["no_said"].get(dest, 0),
                    "proposals_after_second_no": len(after),
                    "tiers_after_second_no": dict(Counter(p["tier"] for p in after)),
                    "still_confirmed": any(r.destination == dest and r.state == State.CONFIRMED
                                           for r in mem.store.routines(["b"]))}
    # U13 forget: the right routine must go, and stay gone while the tombstone lasts
    if gt["forgotten"]:
        dest = gt["forgotten"]["a"]
        fd = log["forget_done"].get(dest)
        fts = datetime.fromisoformat(fd["ts"]) if fd else None
        res = (fd or {}).get("result") or {}
        until = fts + timedelta(days=policy.tombstone_days) if fts else None
        after = [p for p in log["proposals"] if p["scope"] == "a" and p["dest"] == dest and fts
                 and fts < datetime.fromisoformat(p["ts"]) <= until]
        snaps = [s for s in log["snapshots"] if fts and fts < datetime.fromisoformat(s["ts"]) <= until]
        removed_desc = res.get("description", "")
        m["U13"] = {"target": dest, "forgotten_at": fd["ts"] if fd else None, "via": fd["via"] if fd else None,
                    "removed": removed_desc,
                    "removed_right_routine": removed_desc.endswith(f"-> {dest}"),
                    "proposals_within_tombstone": len(after),
                    "reconfirmed_within_tombstone": any(r["scope"] == "a" and r["dest"] == dest
                                                        for s in snaps for r in s["routines"])}
    # U24 acts mid-trip
    probes = log["moving_probes"]
    m["U24"] = {"probes": len(probes), "probes_with_act": sum(1 for p in probes if p["acts"]),
                "acts": dict(Counter(k for p in probes for k in p["acts"]))}
    # U16 / U33 vehicle scope
    vf = {f.key for f in mem.store.facts(["vehicle"], states=[State.CONFIRMED])}
    m["U16"] = {"vehicle_left_rear_sensor": "left_rear_sensor" in vf}
    m["U33"] = {"vehicle_tyre_pressure": "tyre_pressure" in vf}
    # cloud masking incl. U35
    mem.ignition_on(signals["a"], now=end + timedelta(days=1))
    cloud = mem.context("school allergy child seat", "cloud", now=end + timedelta(days=1)).render().lower()
    mem.ignition_off(now=end + timedelta(days=1, hours=1))
    m["cloud_leaks"] = [s for s in gt["cloud_sensitive"] if s.lower() in cloud]
    m["U35"] = {"stored_for_a": any("child seat" in f.describe() for f in mem.store.facts(["a"])),
                "sent_to_cloud": "child seat" in cloud}
    return m


# ---------------------------------------------------------------------------
# driver
# ---------------------------------------------------------------------------

def run_scenario(sc: Scenario, policy: Policy) -> dict[str, Any]:
    if sc.kind == "household" and "traps_planted_on" not in sc.meta:
        dry = CarMemory(":memory:", policy=policy)
        for pid, info in sc.persons.items():
            dry.enroll(pid, info["name"], info["signals"])
        plant_traps(sc, replay(dry, sc, policy))
        dry.close()
    mem = CarMemory(":memory:", policy=policy)
    for pid, info in sc.persons.items():
        mem.enroll(pid, info["name"], info["signals"])
    log = replay(mem, sc, policy)
    if sc.kind == "solo":
        m = score_routines(sc, log, policy, mem, list(sc.persons))
    else:
        m = score_household(sc, log, policy, mem)
    mem.close()
    prov = Counter(e.provenance for e in sc.events)
    return {"name": sc.name, "kind": sc.kind, "meta": sc.meta, "use_cases": sc.use_cases,
            "events_by_provenance": dict(prov), "metrics": m}


def build_all(radius_m: float, households: int, seed: int, swap_rate: float, max_solo: int | None = None):
    raw = load_raw()
    trips, profiles, grid = resolve(raw, radius_m=radius_m)
    solo_ids = solo_candidates(profiles)
    if max_solo:
        solo_ids = sorted(solo_ids, key=lambda v: -profiles[v].trips)[:max_solo]
    scenarios = [build_solo(v, trips[v], profiles[v]) for v in solo_ids]
    pairs = pick_pairs(profiles, households)
    in_pairs = {v for p in pairs for v in p}
    guest_pool = [t for v, ts in trips.items() if v not in in_pairs for t in ts if t.raw.km > 2]
    scenarios += [build_household(a, b, trips, profiles, guest_pool, seed, swap_rate) for a, b in pairs]
    return raw, trips, profiles, grid, scenarios


def aggregate(results: list[dict[str, Any]]) -> dict[str, Any]:
    def pooled(get, rs=None) -> dict[str, Any]:
        n = c = 0
        for r in (results if rs is None else rs):
            x = get(r["metrics"])
            if x and x.get("n"):
                n += x["n"]
                c += round(x["precision"] * x["n"])
        return {"n": n, "precision": round(c / n, 3) if n else None}

    def pooled_ho(get) -> dict[str, Any]:
        n = c = 0
        for r in results:
            x = get(r["metrics"])
            if x and x.get("evaluated"):
                n += x["evaluated"]
                c += round(x["precision"] * x["evaluated"])
        return {"evaluated": n, "precision": round(c / n, 3) if n else None}

    solo = [r for r in results if r["kind"] == "solo"]
    hh = [r for r in results if r["kind"] == "household"]
    agg: dict[str, Any] = {"solo_cars": len(solo), "households": len(hh)}
    agg["heldout_routines"] = pooled_ho(lambda m: m["heldout"])
    agg["heldout_by_kind"] = {k: pooled_ho(lambda m, k=k: m["heldout"]["by_kind"].get(k))
                              for k in ("commute", "to_home", "other")}
    agg["proposals"] = pooled(lambda m: m["proposals"])
    agg["coverage_median"] = st.median(r["metrics"]["coverage"] for r in results) if results else None
    agg["U1"] = {"cars_with_work": sum(1 for r in solo if r["meta"].get("work")),
                 "commute_confirmed": sum(1 for r in solo if r["metrics"]["U1"]["commute_confirmed"]),
                 "median_days_to_first_correct": st.median(
                     [r["metrics"]["U1"]["days_to_first_correct_commute"] for r in solo
                      if r["metrics"]["U1"]["days_to_first_correct_commute"] is not None] or [0])}
    agg["U2"] = {"chained_legs": sum(r["metrics"]["U2"]["chained_legs"] for r in results),
                 "proposals": pooled(lambda m: m["U2"]["proposals"])}
    agg["U4"] = {"irregular_places": sum(r["metrics"]["U4"]["irregular_places"] for r in results),
                 "promoted": sum(r["metrics"]["U4"]["promoted"] for r in results)}
    agg["U5"] = {"stopped_routines": sum(r["metrics"]["U5"]["stopped_routines"] for r in results),
                 "proposals_after_stop": sum(r["metrics"]["U5"]["proposals_after_stop"] for r in results),
                 "max_days_proposed_after_stop": max((r["metrics"]["U5"]["max_days_proposed_after_stop"]
                                                      for r in results), default=0)}
    agg["U6"] = {"break_days": pooled(lambda m: m["U6"]["break_days"]),
                 "other_days": pooled(lambda m: m["U6"]["other_days"])}
    agg["U7"] = {"gaps": sum(r["metrics"]["U7"]["gaps"] for r in results),
                 "proposals_in_14d_after": pooled(lambda m: m["U7"]["proposals_in_14d_after"])}
    errs = [r["metrics"]["U29"]["median_abs_minutes"] for r in results if r["metrics"]["U29"]["median_abs_minutes"]]
    agg["U29"] = {"median_of_car_medians_abs_minutes": st.median(errs) if errs else None}
    if hh:
        agg["household"] = {
            "U8_leaks": sum(len(r["metrics"]["U8"]["leaks"]) for r in hh),
            "U9_b_confirmed_a_routine": sum(r["metrics"]["U9"]["b_confirmed_a_routine"] for r in hh),
            "qa_accuracy_mean": round(st.mean(r["metrics"]["qa_accuracy"] for r in hh), 3),
            "qa_by_uc": {uc: f"{sum(q['ok'] for r in hh for q in r['metrics']['qa'] if q['uc'] == uc)}/"
                             f"{sum(1 for r in hh for q in r['metrics']['qa'] if q['uc'] == uc)}"
                         for uc in sorted({q["uc"] for r in hh for q in r["metrics"]["qa"]})},
            "missing_expected_facts": Counter(x.split("~")[0] for r in hh for x in r["metrics"]["missing_expected_facts"]),
            "settings": {k: pooled(lambda m, k=k: m["settings"].get(k), hh)
                         for k in sorted({k for r in hh for k in r["metrics"]["settings"]})},
            "U20_wrong_person_values": sum(r["metrics"]["U20"]["wrong_person_values"] for r in hh),
            "U11_guest_residue": sum(len(r["metrics"]["U11"]["guest_scopes_remaining"]) +
                                     len(r["metrics"]["U11"]["guest_strings_remaining"]) for r in hh),
            "U23_ok": sum(r["metrics"]["U23"]["ok"] for r in hh),
            "U12": [r["metrics"].get("U12") for r in hh],
            "U13": [r["metrics"].get("U13") for r in hh],
            "U24": {"probes": sum(r["metrics"]["U24"]["probes"] for r in hh),
                    "probes_with_act": sum(r["metrics"]["U24"]["probes_with_act"] for r in hh)},
            "U16_ok": sum(r["metrics"]["U16"]["vehicle_left_rear_sensor"] for r in hh),
            "U33_ok": sum(r["metrics"]["U33"]["vehicle_tyre_pressure"] for r in hh),
            "cloud_leaks": Counter(s for r in hh for s in r["metrics"]["cloud_leaks"]),
            "U35_sent_to_cloud": sum(r["metrics"]["U35"]["sent_to_cloud"] for r in hh),
        }
    return agg


def sweep(scenarios: list[Scenario], base: Policy, ratios: list[float]) -> list[dict[str, Any]]:
    """Held-out precision vs coverage on solo cars as the confirm ratio changes."""
    out = []
    solo = [sc for sc in scenarios if sc.kind == "solo"]
    for x in ratios:
        pol = Policy.from_dict({**base.to_dict(), "routine_confirm_min_ratio": x})
        agg = aggregate([run_scenario(sc, pol) for sc in solo])
        out.append({"routine_confirm_min_ratio": x, "heldout": agg["heldout_routines"],
                    "proposals": agg["proposals"], "coverage_median": agg["coverage_median"],
                    "commute_confirmed": agg["U1"]["commute_confirmed"], "cars_with_work": agg["U1"]["cars_with_work"]})
        print(f"sweep ratio={x}: {out[-1]}", file=sys.stderr)
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--radius", type=float, default=500.0, help="place radius in metres")
    ap.add_argument("--households", type=int, default=6)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--swap-rate", type=float, default=DEFAULT_SWAP_RATE)
    ap.add_argument("--max-solo", type=int, default=None, help="limit solo cars (largest first)")
    ap.add_argument("--policy", default=None)
    ap.add_argument("--sweep", default="0.7,0.5,0.4,0.3,0.2",
                    help="comma-separated routine_confirm_min_ratio values to sweep on solo cars ('' to skip)")
    ap.add_argument("--calibrated-ratio", type=float, default=None,
                    help="also run every scenario with this confirm ratio and report it next to the default")
    ap.add_argument("--out", default=None, help="write full results JSON")
    ap.add_argument("--export", default=None, help="write scenario fixtures to this folder")
    ap.add_argument("--report", default=None, help="write a markdown report")
    args = ap.parse_args(argv)
    policy = Policy.load(args.policy) if args.policy else Policy()
    raw, trips, profiles, grid, scenarios = build_all(args.radius, args.households, args.seed, args.swap_rate,
                                                      args.max_solo)
    results = [run_scenario(sc, policy) for sc in scenarios]
    orc = oracle.run_all(raw, trips, profiles)
    agg = aggregate(results)
    summary = {"radius_m": args.radius, "places": len(grid.cents), "vehicles": len(profiles),
               "trips": len(raw), "policy": {"routine_confirm_min_ratio": policy.routine_confirm_min_ratio},
               "aggregate": agg, "oracle": orc}
    if args.sweep:
        summary["sweep"] = sweep(scenarios, policy, [float(x) for x in args.sweep.split(",")])
    if args.calibrated_ratio is not None:
        cal = Policy.from_dict({**policy.to_dict(), "routine_confirm_min_ratio": args.calibrated_ratio})
        cal_results = [run_scenario(sc, cal) for sc in scenarios]
        summary["calibrated"] = {"routine_confirm_min_ratio": args.calibrated_ratio,
                                 "aggregate": aggregate(cal_results)}
        for r, c in zip(results, cal_results):
            r["calibrated_metrics"] = c["metrics"]
    print(json.dumps(summary, indent=1, default=str))
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps({"summary": summary, "scenarios": results}, indent=1, default=str))
    if args.export:
        from .export import export_all
        export_all(scenarios, results, Path(args.export), args.radius, policy)
    if args.report:
        from .report import write_report
        write_report(Path(args.report), summary, results, profiles)
    return 0


if __name__ == "__main__":
    sys.exit(main())
