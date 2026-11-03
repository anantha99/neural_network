"""Replay a synthetic household through CarMemory and score it.

    python -m bench.run_bench --seed 7 --weeks 8 [--out results.json]
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from datetime import timedelta
from typing import Any

from carmem.consolidation import norm_place
from carmem.memory import CarMemory
from carmem.models import IdentitySignal, State, is_guest
from carmem.policy import Policy

from .synth import HOUSEHOLD, Event, GroundTruth, generate


def signals_for(driver: str) -> list[IdentitySignal]:
    if driver == "guest":
        return [IdentitySignal("voice", "v-unknown", 0.55)]
    return [IdentitySignal(k, v) for k, v in HOUSEHOLD[driver]["signals"]]


def replay(mem: CarMemory, events: list[Event], gt: GroundTruth) -> dict[str, Any]:
    start = events[0].ts
    log = {
        "proposals": [],  # dicts: ts, scope, dest, tier, correct(bool), week
        "no_said": defaultdict(int),
        "forget_done": {},
        "guest_scopes": set(),
    }
    current = None  # (driver, date)
    for ev in events:
        key = (ev.driver, ev.ts.date())
        if ev.type == "sensor":
            mem.record_vehicle_event(ev.payload["key"], ev.payload["value"], now=ev.ts)
            continue
        if key != current:
            if current is not None:
                mem.ignition_off(now=ev.ts - timedelta(minutes=1))
            occ = mem.ignition_on(signals_for(ev.driver), now=ev.ts - timedelta(minutes=5))
            if occ.is_guest:
                log["guest_scopes"].add(occ.driver_id)
            current = key
        if ev.type == "say":
            mem.hear(ev.text, now=ev.ts)
            continue
        # trip: ask the car what it would do, score it, apply driver hooks, then record the trip
        t = ev.trip
        props = mem.proactive(now=t.start - timedelta(minutes=2), location=t.origin)
        for p in props:
            if p.kind != "routine":
                continue
            dest = p.action.get("destination", "")
            correct = norm_place(dest) == norm_place(t.destination)
            log["proposals"].append({"ts": t.start.isoformat(), "scope": p.scope, "dest": dest, "tier": p.tier.value,
                                     "correct": correct, "week": ev.week, "day": (t.start - start).days})
            if any(norm_place(dest) == norm_place(x) for x in ev.reject_if_proposed):
                mem.hear("no", now=t.start - timedelta(minutes=1))
                log["no_said"][dest] += 1
            if any(norm_place(x) in norm_place(dest) for x in ev.forget_if_proposed):
                r = mem.hear("forget that", now=t.start - timedelta(minutes=1))
                log["forget_done"][dest] = {"week": ev.week, "via": "proposal", "result": r.get("forget")}
        if ev.forget_if_proposed and not any(norm_place(x) in norm_place(k) for x in ev.forget_if_proposed for k in log["forget_done"]):
            r = mem.hear(f"forget that I go to the {ev.forget_if_proposed[0]}", now=t.start - timedelta(minutes=1))
            log["forget_done"][ev.forget_if_proposed[0]] = {"week": ev.week, "via": "search", "result": r.get("forget")}
        mem.record_trip(t, now=t.end)
    mem.ignition_off(now=events[-1].ts + timedelta(hours=1))
    return log


def score(mem: CarMemory, log: dict[str, Any], gt: GroundTruth, events: list[Event]) -> dict[str, Any]:
    m: dict[str, Any] = {}
    # --- promotion precision / recall over confirmed routines ---
    confirmed = defaultdict(set)
    for r in mem.store.routines(None, [State.CONFIRMED]):
        confirmed[r.scope].add(r.destination)
    tp = sum(len(confirmed[s] & d) for s, d in gt.expected_confirmed.items())
    n_conf = sum(len(v) for v in confirmed.values())
    n_exp = sum(len(v) for v in gt.expected_confirmed.values())
    m["routine_precision"] = round(tp / n_conf, 3) if n_conf else 0.0
    m["routine_recall"] = round(tp / n_exp, 3) if n_exp else 0.0
    m["confirmed_routines"] = {s: sorted(v) for s, v in confirmed.items()}

    # --- cross-person contamination ---
    leaks = []
    others = {s: set().union(*(d for o, d in gt.expected_confirmed.items() if o != s)) for s in gt.expected_absent}
    for scope, bad in gt.expected_absent.items():
        for r in mem.store.routines([scope]):
            if r.destination in others.get(scope, set()):
                leaks.append(f"{scope}: routine {r.destination} ({r.state.value}) belongs to another person")
            elif r.destination in bad and r.state == State.CONFIRMED:
                leaks.append(f"{scope}: noise routine {r.destination} was confirmed")
        blob = " ".join(
            f.describe() for f in mem.store.facts([scope])) + " " + " ".join(
            e.text for e, _ in mem.store.episodes([scope], limit=10000))
        for s in gt.forbidden_values.get(scope, []):
            if s.lower() in blob.lower():
                leaks.append(f"{scope}: contains '{s}'")
    m["cross_person_leaks"] = leaks

    # --- guest wipe ---
    present = mem.store.scopes_present()
    guest_left = [s for s in present if is_guest(s)]
    everything = " ".join(f.describe() for f in mem.store.facts(None)) + " ".join(
        e.text for e, _ in mem.store.episodes(list(present), limit=100000))
    m["guest_scopes_remaining"] = guest_left
    m["guest_strings_remaining"] = [s for s in gt.guest_strings if s.lower() in everything.lower()]

    # --- proactive quality ---
    props = log["proposals"]
    by_tier = defaultdict(lambda: {"n": 0, "wrong": 0})
    for p in props:
        by_tier[p["tier"]]["n"] += 1
        by_tier[p["tier"]]["wrong"] += int(not p["correct"])
    m["proposals_total"] = len(props)
    m["false_proactive_rate_by_tier"] = {
        t: round(v["wrong"] / v["n"], 3) if v["n"] else 0.0 for t, v in by_tier.items()}
    first_day = {}
    for p in props:
        if p["correct"] and p["scope"] in gt.expected_confirmed and p["dest"] in gt.expected_confirmed[p["scope"]]:
            k = f"{p['scope']}:{p['dest']}"
            first_day[k] = min(first_day.get(k, 10**6), p["day"])
    m["days_to_first_correct_proactive"] = first_day

    # --- correction half-life and resurrection ---
    for scope, dest in gt.corrected.items():
        after = [p for p in props if p["scope"] == scope and p["dest"] == dest and p["week"] >= 5]
        m["correction"] = {
            "target": dest, "no_said": log["no_said"].get(dest, 0),
            "suggestions_after_first_no": max(0, len(after) - 1) if after else 0,
            "still_confirmed": dest in confirmed.get(scope, set()),
        }
    for scope, dest in gt.forgotten.items():
        fd = log["forget_done"].get(dest) or next(iter(log["forget_done"].values()), None)
        fw = fd["week"] if fd else None
        resurrected = [p for p in props if p["scope"] == scope and p["dest"] == dest and fw and p["week"] > fw]
        exists = any(r.destination == dest for r in mem.store.routines([scope]))
        m["forget"] = {"target": dest, "forgotten_in_week": fw, "via": fd["via"] if fd else None,
                       "proposals_after_forget": len(resurrected), "routine_record_exists": exists,
                       "tombstoned": any(norm_place(dest) in t["signature"] for t in mem.store.tombstones(scope))}

    # --- facts ---
    missing = []
    for scope, items in gt.expected_facts.items():
        facts = mem.store.facts([scope], states=[State.CONFIRMED])
        for cat, key, sub in items:
            if not any(f.category == cat and f.key == key and sub.lower() in str(f.value).lower() for f in facts):
                missing.append(f"{scope}:{cat}/{key}~{sub}")
    m["missing_expected_facts"] = missing
    split = [f.describe() for f in mem.store.facts(["asha"], "climate", states=[State.CONFIRMED]) if f.condition]
    m["climate_condition_split"] = split

    # --- QA (LongMemEval-style, scored on the rendered context) ---
    qa_ok, qa_rows = 0, []
    for q in gt.qa:
        mem.ignition_on(signals_for(q["who"]), now=events[-1].ts + timedelta(days=1))
        text = mem.context(q["q"], "local", now=events[-1].ts + timedelta(days=1)).render()
        ok = all(e.lower() in text.lower() for e in q["expect"]) and not any(f.lower() in text.lower() for f in q["forbid"])
        qa_ok += int(ok)
        qa_rows.append({"who": q["who"], "q": q["q"], "ok": ok})
        mem.ignition_off(now=events[-1].ts + timedelta(days=1, hours=1))
    m["qa_accuracy"] = round(qa_ok / len(gt.qa), 3)
    m["qa"] = qa_rows

    # --- cloud masking ---
    mem.ignition_on(signals_for("asha"), now=events[-1].ts + timedelta(days=1))
    cloud = mem.context("school", "cloud", now=events[-1].ts + timedelta(days=1))
    m["cloud_masked_fields"] = len(cloud.masked)
    m["cloud_packet_leaks_sensitive"] = any(s in cloud.render() for s in ("Oakwood", "penicillin"))
    mem.ignition_off(now=events[-1].ts + timedelta(days=2))
    return m


def summarize(m: dict[str, Any]) -> str:
    rows = [
        ("routine precision / recall", f"{m['routine_precision']} / {m['routine_recall']}"),
        ("proposals made", m["proposals_total"]),
        ("false proactive rate by tier", m["false_proactive_rate_by_tier"]),
        ("days to first correct proactive", m["days_to_first_correct_proactive"]),
        ("correction (no's said, suggestions after first no, still confirmed)",
         (m.get("correction", {}).get("no_said"), m.get("correction", {}).get("suggestions_after_first_no"),
          m.get("correction", {}).get("still_confirmed"))),
        ("forget (week, proposals after, record exists, tombstoned)",
         (m.get("forget", {}).get("forgotten_in_week"), m.get("forget", {}).get("proposals_after_forget"),
          m.get("forget", {}).get("routine_record_exists"), m.get("forget", {}).get("tombstoned"))),
        ("cross-person leaks", m["cross_person_leaks"] or "none"),
        ("guest scopes / strings remaining", (m["guest_scopes_remaining"], m["guest_strings_remaining"])),
        ("missing expected facts", m["missing_expected_facts"] or "none"),
        ("climate condition split", m["climate_condition_split"]),
        ("QA accuracy", m["qa_accuracy"]),
        ("cloud: masked fields / sensitive leak", (m["cloud_masked_fields"], m["cloud_packet_leaks_sensitive"])),
    ]
    width = max(len(r[0]) for r in rows)
    return "\n".join(f"{k:<{width}}  {v}" for k, v in rows)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--weeks", type=int, default=8)
    ap.add_argument("--out", default=None)
    ap.add_argument("--policy", default=None, help="policy JSON to evaluate")
    args = ap.parse_args(argv)
    events, gt = generate(args.seed, args.weeks)
    policy = Policy.load(args.policy) if args.policy else Policy()
    mem = CarMemory(":memory:", policy=policy)
    for pid, info in HOUSEHOLD.items():
        mem.enroll(pid, info["name"], info["signals"])
    log = replay(mem, events, gt)
    metrics = score(mem, log, gt, events)
    print(f"carmem benchmark  seed={args.seed} weeks={args.weeks} events={len(events)}\n")
    print(summarize(metrics))
    if args.out:
        with open(args.out, "w") as fh:
            json.dump(metrics, fh, indent=2, default=str)
        print(f"\nwrote {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
