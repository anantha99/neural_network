"""Write real-data scenarios as fixtures.

``fixtures/real/manifest.json`` lists every scenario with its use cases, provenance counts and
source vehicles. Household scenarios (composed + overlays) are written in full, in the same
shape as ``fixtures/household_seed7_w8.json`` plus ``provenance``, ``use_cases`` and ``src`` on
every event. Solo scenarios are pure VED and are rebuilt from ``data/ved/trips.csv.gz``, so only
their manifest entry is written.
"""

from __future__ import annotations

import json
from collections import Counter
from pathlib import Path
from typing import Any

from carmem.models import iso
from carmem.policy import Policy

from .scenarios import RealEvent, Scenario


def event_to_json(ev: RealEvent) -> dict[str, Any]:
    d: dict[str, Any] = {"ts": iso(ev.ts), "type": ev.type, "driver": ev.driver, "provenance": ev.provenance,
                         "use_cases": ev.use_cases}
    if ev.src:
        d["src"] = ev.src
    if ev.trip:
        t = ev.trip
        d["trip"] = {"start": iso(t.start), "end": iso(t.end), "origin": t.origin, "destination": t.destination,
                     "occupants": t.occupants, "settings": t.settings, "route": t.route,
                     "distance_km": round(t.distance_km, 3)}
    if ev.oat is not None:
        d["outside_temp_c"] = ev.oat
    if ev.text:
        d["text"] = ev.text
    if ev.payload:
        d["payload"] = ev.payload
    if ev.reject_if_proposed:
        d["reject_if_proposed"] = ev.reject_if_proposed
    if ev.forget_if_proposed:
        d["forget_if_proposed"] = ev.forget_if_proposed
    if ev.moving_probe:
        d["moving_probe"] = True
    return d


def export_all(scenarios: list[Scenario], results: list[dict[str, Any]], out: Path, radius_m: float,
               policy: Policy) -> None:
    out.mkdir(parents=True, exist_ok=True)
    by_name = {r["name"]: r for r in results}
    manifest = {
        "source": "VED (Oh, LeBlanc, Peng; Apache-2.0, https://github.com/gsoh/VED) via data/ved/trips.csv.gz",
        "place_radius_m": radius_m,
        "rebuild": f"python -m bench.real.run_real --radius {radius_m:g} --export fixtures/real",
        "provenance_values": ["real", "composed", "real_context", "synthetic"],
        "scenarios": [],
    }
    for sc in scenarios:
        entry = {"name": sc.name, "kind": sc.kind, "use_cases": sc.use_cases, "meta": sc.meta,
                 "events": len(sc.events), "events_by_provenance": dict(Counter(e.provenance for e in sc.events))}
        if sc.kind == "household":
            m = by_name[sc.name]["metrics"]
            fx = {
                "name": sc.name, "wire_version": "1",
                "persons": [{"id": pid, "name": info["name"], "signals": [list(s) for s in info["signals"]]}
                            for pid, info in sc.persons.items()],
                "guest_signal": {"kind": "voice", "value": "v-unknown", "confidence": 0.55},
                "policy": policy.to_dict(), "meta": sc.meta, "use_cases": sc.use_cases,
                "events": [event_to_json(e) for e in sc.events],
                "qa": sc.gt["qa"],
                "ground_truth": {k: v for k, v in sc.gt.items() if k != "qa"},
                "expected": {k: m[k] for k in ("U8", "U9", "U11", "U12", "U13", "U16", "U23", "U33", "U35",
                                               "qa_accuracy", "missing_expected_facts", "cloud_leaks") if k in m},
            }
            path = out / f"{sc.name}.json"
            path.write_text(json.dumps(fx, indent=1, default=str))
            entry["file"] = path.name
        manifest["scenarios"].append(entry)
    (out / "manifest.json").write_text(json.dumps(manifest, indent=1, default=str))
    print(f"wrote {out / 'manifest.json'} ({len(scenarios)} scenarios)")
