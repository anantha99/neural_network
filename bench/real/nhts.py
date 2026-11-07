"""Car-sharing rates from the 2022 NextGen National Household Travel Survey (NHTS).

NHTS (FHWA, public domain, https://nhts.ornl.gov/) records every trip a household member
made on their assigned travel day, which household vehicle was used (``VEHID``), and who
drove (``WHODROVE``). The vehicle file names each vehicle's main driver (``WHOMAIN``).
That is enough to measure how often a shared car is driven by someone other than its main
driver - the swap rate the composed households need for U9 - and how often someone from
outside the household drives it (U11).

    curl -LO https://nhts.ornl.gov/media/2022/download/csv.zip   # unzip into a folder
    python -m bench.real.nhts --csv <folder> --out data/nhts/sharing_2022.json

Universe for the swap rate: one record per vehicle trip (the driver's own trip record,
``DRVR_FLG=01``) in a household vehicle (``TRPHHVEH=01``) whose main driver is a household
member (``WHOMAIN`` 01-08), in households with at least two drivers (``DRVRCNT>=2``).
A swap is a trip driven by someone other than ``WHOMAIN``. Estimates use the 7-day trip
weight ``WTTRDFIN``; unweighted counts are reported next to them.
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path
from typing import Any, Callable

DEFAULT_OUT = Path(__file__).resolve().parents[2] / "data" / "nhts" / "sharing_2022.json"


def _rate(rows: list[dict[str, str]], is_hit: Callable[[dict[str, str]], bool]) -> dict[str, Any]:
    w = sum(float(r["WTTRDFIN"]) for r in rows)
    wh = sum(float(r["WTTRDFIN"]) for r in rows if is_hit(r))
    return {"rate": round(wh / w, 4) if w else None, "trips": len(rows), "hits": sum(1 for r in rows if is_hit(r)),
            "households": len({r["HOUSEID"] for r in rows})}


def compute(csv_dir: str | Path) -> dict[str, Any]:
    d = Path(csv_dir)
    main_driver = {(r["HOUSEID"], r["VEHID"]): r["WHOMAIN"] for r in csv.DictReader(open(d / "vehv2pub.csv"))}
    trips = list(csv.DictReader(open(d / "tripv2pub.csv")))

    in_hh_vehicle = [r for r in trips if r["TRPHHVEH"] == "01" and r["VEHID"] not in ("-1", "-9")]
    # who drives a household vehicle at all: household member vs someone else
    # (a non-household driver has no trip record of their own; keep one rider record per vehicle trip)
    seen: set[tuple[str, str, str]] = set()
    one_per_trip = []
    for r in in_hh_vehicle:
        if r["DRVR_FLG"] == "01":
            one_per_trip.append(r)
        elif r["HHMEMDRV"] == "02" and (k := (r["HOUSEID"], r["VEHID"], r["STRTTIME"])) not in seen:
            seen.add(k)
            one_per_trip.append(r)

    def main_of(r):
        return main_driver.get((r["HOUSEID"], r["VEHID"]), "-9")

    shared = [r for r in in_hh_vehicle
              if r["DRVR_FLG"] == "01" and int(r["DRVRCNT"]) >= 2 and main_of(r) in {f"0{i}" for i in range(1, 9)}]
    swap = lambda r: r["PERSONID"] != main_of(r)  # noqa: E731
    weekday = [r for r in shared if r["TDWKND"] == "02"]
    commute = [r for r in weekday if r["TRIPPURP"] == "01"]
    return {
        "source": "2022 NextGen NHTS public use files v2 (FHWA, public domain), https://nhts.ornl.gov/",
        "method": __doc__.split("Universe for the swap rate:")[1].strip(),
        "swap_rate": {
            "all_trips": _rate(shared, swap),
            "weekday": _rate(weekday, swap),
            "weekday_home_based_work": _rate(commute, swap),
        },
        "non_household_driver_rate": _rate(one_per_trip, lambda r: r["HHMEMDRV"] == "02"),
        "used_by_benchmark": "swap_rate.weekday_home_based_work",
        "why": "U9 swaps trips of the primary driver's top weekday routine; weekday home-based-work trips "
               "are the closest NHTS equivalent of a routine trip.",
    }


def load_swap_rate(path: str | Path = DEFAULT_OUT) -> tuple[float, str] | None:
    """The swap rate the benchmark uses, and where it came from; None if not computed."""
    p = Path(path)
    if not p.exists():
        return None
    d = json.loads(p.read_text())
    sect, key = d["used_by_benchmark"].split(".")
    r = d[sect][key]
    return r["rate"], f"NHTS 2022 {key.replace('_', ' ')} trips, n={r['trips']} ({p.name})"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--csv", required=True, help="folder with tripv2pub.csv and vehv2pub.csv")
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    args = ap.parse_args(argv)
    res = compute(args.csv)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(res, indent=1))
    print(json.dumps({k: v for k, v in res.items() if k in ("swap_rate", "non_household_driver_rate")}, indent=1))
    print(f"wrote {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
