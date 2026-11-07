"""Turn the VED trip table into place-labelled trips in local time.

Three things the raw table does not give and this module decides, each written down
so a reader can disagree with a number rather than with a hidden step:

* **Local time.** VED's ``DayNum`` behaves as UTC: read as local time the busiest hour is
  9 pm and 5-8 am is empty; converted to America/Detroit the peaks are 7-8 am and 4-5 pm.
  We convert.
* **Places.** Trip ends are fogged (cut to the first/last major intersection plus random
  masking), so the end of one trip and the start of the next are typically ~1.8 km apart.
  Endpoints are clustered on one shared grid for all cars (so a campus or a mall is the same
  place for everyone) with ``radius_m`` (default 1000 m; the head unit uses 150 m on clean
  GPS).
* **Origin continuity.** A parked car starts where it stopped. If a trip starts within
  ``continuity_m`` of the previous trip's end and less than ``continuity_h`` hours later, its
  origin is the previous destination; otherwise its own nearest place.

Home is the place with the most overnight dwell (arrive after 17:00, leave the next day
before 11:00); work is the place with the most weekday daytime dwell (>= 4 h between 07:00
and 19:00), excluding home. Other places are named ``p0001`` etc.
"""

from __future__ import annotations

import csv
import gzip
import math
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

LOCAL = ZoneInfo("America/Detroit")
DEFAULT_TRIPS = Path(__file__).resolve().parents[2] / "data" / "ved" / "trips.csv.gz"


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    p = math.radians
    x = math.sin(p(lat2 - lat1) / 2) ** 2 + math.cos(p(lat1)) * math.cos(p(lat2)) * math.sin(p(lon2 - lon1) / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(x))


def to_local(s: str) -> datetime:
    """VED clock (UTC) -> naive local Detroit time, which is what CarMemory works in."""
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc).astimezone(LOCAL).replace(tzinfo=None)


@dataclass
class RawTrip:
    veh: int
    trip: int
    start: datetime
    end: datetime
    lat0: float
    lon0: float
    lat1: float
    lon1: float
    km: float
    oat: float | None
    ac_on5: int | None
    heat_on5: int | None
    fast_frac: float
    soc0: float | None
    soc1: float | None


@dataclass
class PlaceTrip:
    """One real trip with places resolved. ``origin``/``destination`` are global place ids
    (``p0042``); ``origin_name``/``dest_name`` are the per-car labels (home, work, p0042)."""

    raw: RawTrip
    origin: str
    destination: str
    origin_name: str = ""
    dest_name: str = ""
    continuity: bool = False

    @property
    def veh(self) -> int:
        return self.raw.veh

    @property
    def start(self) -> datetime:
        return self.raw.start

    @property
    def end(self) -> datetime:
        return self.raw.end


def _opt(s: str, cast=float):
    return None if s == "" else cast(float(s)) if cast is int else cast(s)


def load_raw(path: str | Path = DEFAULT_TRIPS) -> list[RawTrip]:
    out = []
    with gzip.open(path, "rt", newline="") as fh:
        for r in csv.DictReader(fh):
            out.append(RawTrip(
                int(r["veh"]), int(r["trip"]), to_local(r["start"]), to_local(r["end"]),
                float(r["lat0"]), float(r["lon0"]), float(r["lat1"]), float(r["lon1"]), float(r["km"]),
                _opt(r["oat"]), _opt(r["ac_on5"], int), _opt(r["heat_on5"], int), float(r["fast_frac"]),
                _opt(r["soc0"]), _opt(r["soc1"]),
            ))
    out.sort(key=lambda t: (t.veh, t.start))
    return out


class PlaceGrid:
    """Leader clustering on a lat/lon grid: a point joins the nearest centroid within
    ``radius_m``, else starts a new place. Deterministic for a fixed input order."""

    def __init__(self, radius_m: float):
        self.r = radius_m
        self.cell = radius_m / 111_000.0  # degrees latitude per cell; longitude cells are wider, still safe
        self.cents: list[list[float]] = []  # lat, lon, n
        self.grid: dict[tuple[int, int], list[int]] = defaultdict(list)

    def _key(self, lat: float, lon: float) -> tuple[int, int]:
        return int(math.floor(lat / self.cell)), int(math.floor(lon / self.cell))

    def nearest(self, lat: float, lon: float) -> tuple[int | None, float]:
        ki, kj = self._key(lat, lon)
        best, bd = None, float("inf")
        for di in (-1, 0, 1):
            for dj in (-2, -1, 0, 1, 2):
                for i in self.grid.get((ki + di, kj + dj), ()):
                    c = self.cents[i]
                    d = haversine_m(lat, lon, c[0], c[1])
                    if d < bd:
                        best, bd = i, d
        return best, bd

    def add(self, lat: float, lon: float) -> int:
        i, d = self.nearest(lat, lon)
        if i is not None and d <= self.r:
            c = self.cents[i]
            c[0] = (c[0] * c[2] + lat) / (c[2] + 1)
            c[1] = (c[1] * c[2] + lon) / (c[2] + 1)
            c[2] += 1
            # the centroid may drift into another cell; index it there too
            k = self._key(c[0], c[1])
            if i not in self.grid[k]:
                self.grid[k].append(i)
            return i
        self.cents.append([lat, lon, 1])
        i = len(self.cents) - 1
        self.grid[self._key(lat, lon)].append(i)
        return i


def pid(i: int) -> str:
    return f"p{i:04d}"


@dataclass
class CarProfile:
    veh: int
    trips: int
    active_weeks: int
    active_days: int
    first: datetime
    last: datetime
    home: str | None
    work: str | None
    has_oat: bool
    has_climate: bool
    has_soc: bool
    names: dict[str, str] = field(default_factory=dict)


def resolve(raw: list[RawTrip], radius_m: float = 1000.0, continuity_m: float = 2500.0,
            continuity_h: float = 18.0) -> tuple[dict[int, list[PlaceTrip]], dict[int, CarProfile], PlaceGrid]:
    grid = PlaceGrid(radius_m)
    # destinations first, in time order, so places are anchored on where cars stop
    for t in sorted(raw, key=lambda t: t.start):
        grid.add(t.lat1, t.lon1)
    by: dict[int, list[PlaceTrip]] = defaultdict(list)
    for t in raw:
        dest, _ = grid.nearest(t.lat1, t.lon1)
        by[t.veh].append(PlaceTrip(t, "", pid(dest)))
    for veh, ts in by.items():
        prev: PlaceTrip | None = None
        for pt in ts:
            t = pt.raw
            if prev is not None:
                gap_h = (t.start - prev.raw.end).total_seconds() / 3600
                c = grid.cents[int(prev.destination[1:])]
                if 0 <= gap_h <= continuity_h and haversine_m(t.lat0, t.lon0, c[0], c[1]) <= continuity_m:
                    pt.origin, pt.continuity = prev.destination, True
            if not pt.origin:
                i, d = grid.nearest(t.lat0, t.lon0)
                pt.origin = pid(i) if i is not None and d <= radius_m else pid(grid.add(t.lat0, t.lon0))
            prev = pt
    profiles = {veh: _profile(veh, ts) for veh, ts in by.items()}
    for veh, ts in by.items():
        names = profiles[veh].names
        for pt in ts:
            pt.origin_name = names.get(pt.origin, pt.origin)
            pt.dest_name = names.get(pt.destination, pt.destination)
    return dict(by), profiles, grid


def _profile(veh: int, ts: list[PlaceTrip]) -> CarProfile:
    night: Counter[str] = Counter()
    day: Counter[str] = Counter()
    for a, b in zip(ts, ts[1:]):
        arr, dep = a.raw.end, b.raw.start
        if arr.hour >= 17 and dep.date() == arr.date() + timedelta(days=1) and dep.hour < 11:
            night[a.destination] += 1
        if arr.weekday() < 5 and arr.date() == dep.date() and 7 <= arr.hour and dep.hour < 19 \
                and (dep - arr) >= timedelta(hours=4):
            day[a.destination] += 1
    home = night.most_common(1)[0][0] if night and night.most_common(1)[0][1] >= 3 else None
    work = next((p for p, n in day.most_common() if p != home and n >= 3), None)
    names = {}
    if home:
        names[home] = "home"
    if work:
        names[work] = "work"
    starts = [t.raw.start for t in ts]
    return CarProfile(
        veh=veh, trips=len(ts), active_weeks=len({s.isocalendar()[:2] for s in starts}),
        active_days=len({s.date() for s in starts}), first=min(starts), last=max(starts), home=home, work=work,
        has_oat=any(t.raw.oat is not None for t in ts),
        has_climate=any(t.raw.ac_on5 is not None for t in ts),
        has_soc=any(t.raw.soc0 is not None for t in ts), names=names,
    )
