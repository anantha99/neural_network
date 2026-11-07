"""Summarise the Vehicle Energy Dataset (VED) into one row per trip.

VED (Oh, LeBlanc, Peng; Apache-2.0, https://github.com/gsoh/VED) logs 383 personal
cars in Ann Arbor, Michigan, Nov 2017 - Nov 2018, at roughly 1-5 Hz. The raw data is
about 3 GB of weekly CSVs; this module reduces it to a small trip table that the rest
of the real-data track uses and that is committed to the repository.

    git clone --depth 1 https://github.com/gsoh/VED.git
    # extract Data/VED_DynamicData_Part1.7z and Part2.7z into one folder of CSVs
    python -m bench.real.ved --raw path/to/csvs --out data/ved/trips.csv.gz

Columns written per trip (times are VED's clock, which is UTC; places.py converts):
    veh, trip, start, end            ISO seconds, UTC
    lat0, lon0, lat1, lon1           first and last GPS fix (VED fogs trip ends)
    km                               distance from integrated speed
    oat                              outside air temperature at start, deg C (blank if not logged)
    ac_on5, heat_on5                 AC / heater drawing power in the first 5 minutes (1/0, blank if not logged)
    fast_frac                        share of moving time above 80 km/h
    soc0, soc1                       HV battery state of charge at start and end, % (EV/PHEV only)
"""

from __future__ import annotations

import argparse
import csv
import gzip
import math
import sys
from concurrent.futures import ProcessPoolExecutor
from datetime import datetime, timedelta
from pathlib import Path

# DayNum 1 = 1 Nov 2017 00:00 (VED README)
DAY0 = datetime(2017, 10, 31)

COLS = {
    "day": "DayNum", "veh": "VehId", "trip": "Trip", "ms": "Timestamp(ms)",
    "lat": "Latitude[deg]", "lon": "Longitude[deg]", "speed": "Vehicle Speed[km/h]",
    "ac_kw": "Air Conditioning Power[kW]", "ac_w": "Air Conditioning Power[Watts]",
    "heat_w": "Heater Power[Watts]", "soc": "HV Battery SOC[%]",
}
OAT_NAMES = ("OAT[DegC]", "Outside Air Temperature[DegC]")
FIELDS = ["veh", "trip", "start", "end", "lat0", "lon0", "lat1", "lon1", "km", "oat",
          "ac_on5", "heat_on5", "fast_frac", "soc0", "soc1"]

AC_KW_ON = 0.1
AC_W_ON = 100.0
HEAT_W_ON = 100.0
FIRST_MS = 5 * 60 * 1000


def _f(s: str) -> float | None:
    if not s or s == "NaN":
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return None if math.isnan(v) else v


class _Acc:
    __slots__ = ("day", "ms0", "ms1", "lat0", "lon0", "lat1", "lon1", "km", "oat", "ac_seen", "ac_on",
                 "heat_seen", "heat_on", "move_ms", "fast_ms", "soc0", "soc1", "prev_ms", "prev_v")

    def __init__(self, day: float):
        self.day = day
        self.ms0 = self.ms1 = None
        self.lat0 = self.lon0 = self.lat1 = self.lon1 = None
        self.km = 0.0
        self.oat = None
        self.ac_seen = self.ac_on = self.heat_seen = self.heat_on = False
        self.move_ms = self.fast_ms = 0.0
        self.soc0 = self.soc1 = None
        self.prev_ms = self.prev_v = None


def summarise_file(path: str) -> list[dict]:
    accs: dict[tuple[int, int], _Acc] = {}
    with open(path, newline="") as fh:
        rd = csv.reader(fh)
        header = next(rd)
        ix = {k: header.index(v) for k, v in COLS.items()}
        ix["oat"] = next(header.index(n) for n in OAT_NAMES if n in header)
        for row in rd:
            try:
                key = (int(float(row[ix["veh"]])), int(float(row[ix["trip"]])))
                ms = float(row[ix["ms"]])
            except (ValueError, IndexError):
                continue
            a = accs.get(key)
            if a is None:
                a = accs[key] = _Acc(float(row[ix["day"]]))
            lat, lon = _f(row[ix["lat"]]), _f(row[ix["lon"]])
            v = _f(row[ix["speed"]])
            if a.ms0 is None or ms < a.ms0:
                a.ms0 = ms
                if lat is not None:
                    a.lat0, a.lon0 = lat, lon
            if a.ms1 is None or ms >= a.ms1:
                a.ms1 = ms
                if lat is not None:
                    a.lat1, a.lon1 = lat, lon
            if a.lat0 is None and lat is not None:
                a.lat0, a.lon0 = lat, lon
            if a.prev_ms is not None and a.prev_v is not None and ms > a.prev_ms:
                dt = min(ms - a.prev_ms, 10_000.0)  # cap gaps at 10 s
                a.km += a.prev_v * dt / 3_600_000.0
                if a.prev_v > 2:
                    a.move_ms += dt
                    if a.prev_v > 80:
                        a.fast_ms += dt
            a.prev_ms, a.prev_v = ms, v
            oat = _f(row[ix["oat"]])
            if a.oat is None and oat is not None and -40 < oat < 60:
                a.oat = oat
            early = ms - (a.ms0 or 0) <= FIRST_MS
            ackw, acw, hw = _f(row[ix["ac_kw"]]), _f(row[ix["ac_w"]]), _f(row[ix["heat_w"]])
            if ackw is not None or acw is not None:
                a.ac_seen = True
                if early and ((ackw or 0) > AC_KW_ON or (acw or 0) > AC_W_ON):
                    a.ac_on = True
            if hw is not None:
                a.heat_seen = True
                if early and hw > HEAT_W_ON:
                    a.heat_on = True
            soc = _f(row[ix["soc"]])
            if soc is not None and 0 <= soc <= 100:
                if a.soc0 is None:
                    a.soc0 = soc
                a.soc1 = soc
    out = []
    for (veh, trip), a in accs.items():
        if a.ms0 is None or a.lat0 is None or a.lat1 is None:
            continue
        start = DAY0 + timedelta(days=a.day)
        end = start + timedelta(milliseconds=a.ms1 - a.ms0)
        out.append({
            "veh": veh, "trip": trip,
            "start": start.replace(microsecond=0).isoformat(), "end": end.replace(microsecond=0).isoformat(),
            "lat0": round(a.lat0, 5), "lon0": round(a.lon0, 5), "lat1": round(a.lat1, 5), "lon1": round(a.lon1, 5),
            "km": round(a.km, 2), "oat": "" if a.oat is None else round(a.oat, 1),
            "ac_on5": "" if not a.ac_seen else int(a.ac_on), "heat_on5": "" if not a.heat_seen else int(a.heat_on),
            "fast_frac": round(a.fast_ms / a.move_ms, 3) if a.move_ms else 0.0,
            "soc0": "" if a.soc0 is None else round(a.soc0, 1), "soc1": "" if a.soc1 is None else round(a.soc1, 1),
        })
    return out


def summarise(raw_dir: str | Path, workers: int = 4) -> list[dict]:
    files = sorted(str(p) for p in Path(raw_dir).glob("*.csv"))
    if not files:
        raise SystemExit(f"no CSV files in {raw_dir}")
    rows: dict[tuple[int, int], dict] = {}
    with ProcessPoolExecutor(max_workers=workers) as ex:
        for part in ex.map(summarise_file, files):
            for r in part:
                k = (r["veh"], r["trip"])
                if k not in rows or r["end"] > rows[k]["end"]:  # a trip split across files keeps its longest record
                    rows[k] = r
    return sorted(rows.values(), key=lambda r: (r["veh"], r["start"]))


def write_trips(rows: list[dict], out: str | Path) -> None:
    out = Path(out)
    out.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(out, "wt", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--raw", required=True, help="folder holding the extracted VED_*_week.csv files")
    ap.add_argument("--out", default="data/ved/trips.csv.gz")
    ap.add_argument("--workers", type=int, default=4)
    args = ap.parse_args(argv)
    rows = summarise(args.raw, args.workers)
    write_trips(rows, args.out)
    print(f"wrote {args.out}: {len(rows)} trips, {len({r['veh'] for r in rows})} vehicles")
    return 0


if __name__ == "__main__":
    sys.exit(main())
