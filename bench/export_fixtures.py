"""Export conformance fixtures: the concrete event log plus what the reference produces.

    python -m bench.export_fixtures --seed 7 --weeks 8 --out fixtures/

Any other implementation (the Kotlin head-unit service) replays ``events`` with
the protocol in ``wire-schema.json`` and must reproduce ``expected``.
"""

from __future__ import annotations

import argparse
import json
from collections import defaultdict
from dataclasses import asdict
from datetime import timedelta
from pathlib import Path

from carmem.memory import CarMemory
from carmem.models import State, iso
from carmem.wire import dump_wire_schema

from .run_bench import replay, score, signals_for
from .synth import HOUSEHOLD, generate


def event_to_json(ev) -> dict:
    d = {"ts": iso(ev.ts), "type": ev.type, "driver": ev.driver, "week": ev.week}
    if ev.trip:
        t = ev.trip
        d["trip"] = {"start": iso(t.start), "end": iso(t.end), "origin": t.origin, "destination": t.destination,
                     "occupants": t.occupants, "settings": t.settings, "route": t.route,
                     "distance_km": round(t.distance_km, 3)}
    if ev.text:
        d["text"] = ev.text
    if ev.payload:
        d["payload"] = ev.payload
    if ev.reject_if_proposed:
        d["reject_if_proposed"] = ev.reject_if_proposed
    if ev.forget_if_proposed:
        d["forget_if_proposed"] = ev.forget_if_proposed
    return d


def main(argv=None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--weeks", type=int, default=8)
    ap.add_argument("--out", default="fixtures")
    args = ap.parse_args(argv)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    events, gt = generate(args.seed, args.weeks)
    mem = CarMemory()
    for pid, info in HOUSEHOLD.items():
        mem.enroll(pid, info["name"], info["signals"])
    log = replay(mem, events, gt)
    metrics = score(mem, log, gt, events)

    facts = defaultdict(list)
    for f in mem.store.facts(None, states=[State.CONFIRMED]):
        facts[f.scope].append(f.describe())
    for v in facts.values():
        v.sort()

    fixture = {
        "name": f"household_seed{args.seed}_w{args.weeks}",
        "wire_version": "1",
        "persons": [{"id": pid, "name": info["name"], "signals": [list(s) for s in info["signals"]]}
                    for pid, info in HOUSEHOLD.items()],
        "guest_signal": {"kind": "voice", "value": "v-unknown", "confidence": 0.55},
        "policy": mem.policy.to_dict(),
        "events": [event_to_json(e) for e in events],
        "qa": gt.qa,
        "expected": {
            "confirmed_routines": metrics["confirmed_routines"],
            "confirmed_facts": dict(facts),
            "proposals": [{k: p[k] for k in ("ts", "scope", "dest", "tier", "correct")} for p in log["proposals"]],
            "no_said": dict(log["no_said"]),
            "forget": metrics["forget"],
            "correction": metrics["correction"],
            "cross_person_leaks": metrics["cross_person_leaks"],
            "guest_scopes_remaining": metrics["guest_scopes_remaining"],
            "qa_accuracy": metrics["qa_accuracy"],
            "cloud_masked_fields": metrics["cloud_masked_fields"],
            "tombstones": [{"scope": t["scope"], "signature": t["signature"]} for t in mem.store.tombstones()],
        },
    }
    (out / f"{fixture['name']}.json").write_text(json.dumps(fixture, indent=1, default=str))
    (out / "wire-schema.json").write_text(dump_wire_schema())
    print(f"wrote {out / (fixture['name'] + '.json')} ({len(events)} events, {len(log['proposals'])} proposals)")
    print(f"wrote {out / 'wire-schema.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
