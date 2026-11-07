"""Use cases that real data covers but carmem cannot learn yet (U17, U18, U28, U30).

For each one we measure how learnable the behaviour is from the data with a trivial
model, trained on odd ISO weeks and tested on even weeks. That says whether building
the feature is worth it and gives the number carmem should beat once it exists.
"""

from __future__ import annotations

import statistics as st
from collections import Counter, defaultdict
from typing import Any

from .places import CarProfile, PlaceTrip, RawTrip


def _odd(t) -> bool:
    return t.isocalendar()[1] % 2 == 1


def _threshold_model(train: list[tuple[float, int]]) -> tuple[float, int]:
    """Best single threshold: predict ``on`` when oat > T (direction 1) or oat < T (direction -1)."""
    best = (0.0, 1, -1.0)
    temps = sorted({round(x, 0) for x, _ in train})
    for T in temps:
        for d in (1, -1):
            acc = sum((x > T if d == 1 else x < T) == bool(y) for x, y in train) / len(train)
            if acc > best[2]:
                best = (T, d, acc)
    return best[0], best[1]


def _onoff(raw: list[RawTrip], field: str) -> dict[str, Any]:
    by: dict[int, list[RawTrip]] = defaultdict(list)
    for t in raw:
        if t.oat is not None and getattr(t, field) is not None:
            by[t.veh].append(t)
    rows = []
    for veh, ts in by.items():
        tr = [(t.oat, getattr(t, field)) for t in ts if _odd(t.start)]
        te = [(t.oat, getattr(t, field)) for t in ts if not _odd(t.start)]
        if len(tr) < 20 or len(te) < 20:
            continue
        T, d = _threshold_model(tr)
        acc = sum((x > T if d == 1 else x < T) == bool(y) for x, y in te) / len(te)
        maj = round(sum(y for _, y in tr) / len(tr) >= 0.5)
        base = sum(y == maj for _, y in te) / len(te)
        rows.append({"veh": veh, "threshold_c": T, "on_when": "above" if d == 1 else "below",
                     "test_accuracy": round(acc, 3), "majority_baseline": round(base, 3), "test_trips": len(te)})
    return {
        "cars_with_data": len(by), "cars_evaluated": len(rows),
        "mean_accuracy": round(st.mean(r["test_accuracy"] for r in rows), 3) if rows else None,
        "mean_baseline": round(st.mean(r["majority_baseline"] for r in rows), 3) if rows else None,
        "cars": rows,
    }


def u17_climate_habit(raw: list[RawTrip]) -> dict[str, Any]:
    return {"status": "real data; carmem has no AC/heater-vs-outside-temperature model",
            "ac": _onoff(raw, "ac_on5"), "heater": _onoff(raw, "heat_on5")}


def u18_precondition(trips: dict[int, list[PlaceTrip]]) -> dict[str, Any]:
    """On cold weekday mornings, how often is the heater on within 5 minutes of a routine trip?"""
    rows = []
    for veh, ts in trips.items():
        cold = [t for t in ts if t.raw.heat_on5 is not None and t.raw.oat is not None and t.raw.oat < 5
                and t.start.weekday() < 5 and t.start.hour < 11]
        hot = [t for t in ts if t.raw.ac_on5 is not None and t.raw.oat is not None and t.raw.oat > 25]
        if len(cold) >= 5 or len(hot) >= 5:
            rows.append({"veh": veh, "cold_morning_trips": len(cold),
                         "heater_on_share": round(sum(t.raw.heat_on5 for t in cold) / len(cold), 3) if cold else None,
                         "hot_trips": len(hot),
                         "ac_on_share": round(sum(t.raw.ac_on5 for t in hot) / len(hot), 3) if hot else None})
    return {"status": "real data; carmem has no pre-conditioning action", "cars": rows}


def u28_motorway(trips: dict[int, list[PlaceTrip]], profiles: dict[int, CarProfile]) -> dict[str, Any]:
    pairs = []
    for veh, ts in trips.items():
        a = [t.raw.fast_frac for t in ts if _odd(t.start) and t.raw.km > 3]
        b = [t.raw.fast_frac for t in ts if not _odd(t.start) and t.raw.km > 3]
        if len(a) >= 15 and len(b) >= 15:
            pairs.append((st.mean(a), st.mean(b)))
    if len(pairs) < 3:
        return {"status": "real data; too few cars", "cars": len(pairs)}
    xs, ys = zip(*pairs)
    r = st.correlation(xs, ys)
    agree = sum((x > 0.2) == (y > 0.2) for x, y in pairs) / len(pairs)
    return {"status": "real data; carmem learns route preferences only from speech",
            "cars": len(pairs), "odd_even_week_correlation": round(r, 3),
            "motorway_user_label_agreement": round(agree, 3),
            "motorway_users_share": round(sum(x > 0.2 for x in xs) / len(xs), 3)}


def u30_charging(trips: dict[int, list[PlaceTrip]]) -> dict[str, Any]:
    rows = []
    for veh, ts in trips.items():
        ev = []
        for a, b in zip(ts, ts[1:]):
            if a.raw.soc1 is not None and b.raw.soc0 is not None and b.raw.soc0 - a.raw.soc1 >= 10:
                overnight = b.start.date() > a.end.date()
                ev.append((a.start, a.dest_name, overnight))
        if len(ev) < 6:
            continue
        tr = [e for e in ev if _odd(e[0])]
        te = [e for e in ev if not _odd(e[0])]
        if not tr or not te:
            continue
        top = Counter(p for _, p, _ in tr).most_common(1)[0][0]
        rows.append({"veh": veh, "charge_events": len(ev), "top_place": top,
                     "test_at_top_place": round(sum(p == top for _, p, _ in te) / len(te), 3),
                     "overnight_share": round(sum(o for *_, o in ev) / len(ev), 3)})
    return {"status": "real data; carmem has no charging memory", "cars": rows,
            "mean_test_at_top_place": round(st.mean(r["test_at_top_place"] for r in rows), 3) if rows else None}


def run_all(raw: list[RawTrip], trips: dict[int, list[PlaceTrip]], profiles: dict[int, CarProfile]) -> dict[str, Any]:
    return {"U17": u17_climate_habit(raw), "U18": u18_precondition(trips),
            "U28": u28_motorway(trips, profiles), "U30": u30_charging(trips)}
