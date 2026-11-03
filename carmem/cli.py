"""Command line: `carmem demo`, `carmem bench`, `carmem policy`."""

from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta

from .memory import CarMemory
from .models import IdentitySignal, Trip
from .policy import Policy


def demo(db: str = ":memory:") -> None:
    mem = CarMemory(db)
    mem.enroll("asha", "Asha", [("key_fob", "fob-1"), ("phone", "asha-phone")])
    mem.enroll("ben", "Ben", [("phone", "ben-phone")])
    start = datetime(2026, 9, 7, 7, 40)  # a Monday
    print("== four weeks of school runs ==")
    for week in range(4):
        for day in range(5):
            t0 = start + timedelta(weeks=week, days=day)
            mem.ignition_on([IdentitySignal("key_fob", "fob-1")], now=t0)
            props = mem.proactive(now=t0)
            for p in props:
                print(f"{t0:%a %d %b %H:%M}  [{p.tier.value}] {p.text}")
            mem.record_trip(Trip(t0, t0 + timedelta(minutes=20), "home", "Oakwood Primary", ["asha"], {"cabin_temp": 21}))
            if week == 0 and day == 0:
                print(" asha:", "my kid's school is Oakwood Primary")
                mem.hear("my kid's school is Oakwood Primary", now=t0)
            mem.ignition_off(now=t0 + timedelta(hours=1))
    t = start + timedelta(weeks=4)
    mem.ignition_on([IdentitySignal("key_fob", "fob-1")], now=t)
    print("\n== context packet for a cloud model ==")
    print(mem.context("school", destination="cloud", now=t).render())
    print("\n== driver says no to the suggestion ==")
    mem.proactive(now=t)
    print(json.dumps(mem.hear("no", now=t)["correction"], indent=2))
    print("\n== a guest drives ==")
    mem.ignition_off(now=t + timedelta(minutes=5))
    occ = mem.ignition_on([IdentitySignal("voice", "unknown", 0.6)], now=t + timedelta(hours=2))
    print("guest scope:", occ.driver_id)
    mem.hear("my home is 12 Elm Street", now=t + timedelta(hours=2))
    print("guest facts before ignition off:", len(mem.store.facts([occ.driver_id])))
    wiped = mem.ignition_off(now=t + timedelta(hours=3))
    print("wiped:", wiped["wiped"])


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(prog="carmem")
    sub = ap.add_subparsers(dest="cmd", required=True)
    d = sub.add_parser("demo", help="run a scripted month and print what the car learns")
    d.add_argument("--db", default=":memory:")
    b = sub.add_parser("bench", help="run the synthetic driver-log benchmark")
    b.add_argument("--seed", type=int, default=7)
    b.add_argument("--weeks", type=int, default=8)
    b.add_argument("--out", default=None, help="write metrics JSON here")
    sub.add_parser("policy", help="print the default policy as JSON")
    args = ap.parse_args(argv)
    if args.cmd == "demo":
        demo(args.db)
    elif args.cmd == "bench":
        from bench.run_bench import main as bench_main

        return bench_main(["--seed", str(args.seed), "--weeks", str(args.weeks)] + (["--out", args.out] if args.out else []))
    elif args.cmd == "policy":
        print(Policy().to_json())
    return 0


if __name__ == "__main__":
    sys.exit(main())
