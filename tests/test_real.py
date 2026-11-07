"""Real-data track: place resolution, scenario provenance, and one composed household."""

from datetime import datetime

import pytest

from bench.real.places import DEFAULT_TRIPS, PlaceGrid, load_raw, resolve, to_local
from bench.real.run_real import build_all, run_scenario
from carmem.policy import Policy

pytestmark = pytest.mark.skipif(not DEFAULT_TRIPS.exists(), reason="data/ved/trips.csv.gz not present")


def test_ved_clock_is_converted_to_detroit_time():
    assert to_local("2018-01-15T12:30:00") == datetime(2018, 1, 15, 7, 30)  # EST, UTC-5
    assert to_local("2018-07-15T12:30:00") == datetime(2018, 7, 15, 8, 30)  # EDT, UTC-4


def test_place_grid_joins_within_radius_only():
    g = PlaceGrid(500)
    a = g.add(42.28, -83.74)
    assert g.add(42.2830, -83.7400) == a  # ~330 m north
    assert g.add(42.2900, -83.7400) != a  # ~1.1 km north


def test_real_trips_have_rush_hours_in_local_time():
    raw = load_raw()
    hours = [t.start.hour for t in raw if t.start.weekday() < 5]
    assert sum(7 <= h < 9 for h in hours) > 5 * sum(2 <= h < 4 for h in hours)


def test_household_scenario_keeps_people_apart():
    _, _, _, _, scs = build_all(500, households=1, seed=7, swap_rate=0.1, max_solo=1)
    solo = [s for s in scs if s.kind == "solo"][0]
    assert {e.provenance for e in solo.events} == {"real"}
    hh = [s for s in scs if s.kind == "household"][0]
    assert {"real", "composed", "synthetic"} <= {e.provenance for e in hh.events}
    m = run_scenario(hh, Policy())["metrics"]
    assert m["U8"]["leaks"] == []
    assert m["U11"]["guest_scopes_remaining"] == [] and m["U11"]["guest_strings_remaining"] == []
    assert m["U20"]["wrong_person_values"] == 0
    assert all(q["ok"] for q in m["qa"] if q["uc"] == "U10")
