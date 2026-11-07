"""Render the real-data benchmark results as markdown (docs/REAL_BENCH_RESULTS.md)."""

from __future__ import annotations

from pathlib import Path
from typing import Any


def _p(x: dict[str, Any] | None, key: str = "precision", n: str = "n") -> str:
    if not x or x.get(key) is None:
        return "n/a"
    return f"{x[key]:.2f} (n={x.get(n, x.get('evaluated'))})"


def _uc_rows(s: dict[str, Any]) -> list[tuple[str, str, str, str, str]]:
    a = s["aggregate"]
    c = (s.get("calibrated") or {}).get("aggregate", {})
    h = a.get("household", {})
    ch = c.get("household", {})
    o = s["oracle"]
    cal = lambda f: f(c) if c else "-"  # noqa: E731

    def u12(hh: dict[str, Any]) -> str:
        rows = [x for x in hh.get("U12", []) if x]
        fired = [x for x in rows if x["no_said"]]
        if not fired:
            return f"trap never fired: no routine of B's was proposed ({len(rows)} households)"
        after = sum(x["proposals_after_second_no"] for x in fired)
        tiers = {}
        for x in fired:
            for k, v in x["tiers_after_second_no"].items():
                tiers[k] = tiers.get(k, 0) + v
        return f"fired in {len(fired)}/{len(rows)}; proposals after 2nd no: {after} {tiers or ''}"

    def u13(hh: dict[str, Any]) -> str:
        rows = [x for x in hh.get("U13", []) if x and x.get("forgotten_at")]
        if not rows:
            return "trap never fired"
        right = sum(x["removed_right_routine"] for x in rows)
        res = sum(x["proposals_within_tombstone"] for x in rows)
        rec = sum(x["reconfirmed_within_tombstone"] for x in rows)
        via = {}
        for x in rows:
            via[x["via"]] = via.get(x["via"], 0) + 1
        return (f"{len(rows)} forgets {via}; right routine removed {right}/{len(rows)}; "
                f"proposals within tombstone {res}; re-confirmed {rec}")

    def settings(hh: dict[str, Any], key: str) -> str:
        return _p(hh.get("settings", {}).get(key)) if hh else "-"

    hhn = a.get("households", 0)
    return [
        ("U1", "Weekday commute", "real",
         f"{a['U1']['commute_confirmed']}/{a['U1']['cars_with_work']} cars with a work place got a commute routine; "
         f"held-out {_p(a['heldout_by_kind']['commute'], n='evaluated')}",
         cal(lambda c: f"{c['U1']['commute_confirmed']}/{c['U1']['cars_with_work']}; held-out "
                       f"{_p(c['heldout_by_kind']['commute'], n='evaluated')}")),
        ("U2", "Chained stops", "real", f"{a['U2']['chained_legs']} legs; proposal precision {_p(a['U2']['proposals'])}",
         cal(lambda c: _p(c["U2"]["proposals"]))),
        ("U3", "Low-frequency weekly", "real", f"held-out {_p(a['heldout_by_kind']['other'], n='evaluated')}",
         cal(lambda c: _p(c["heldout_by_kind"]["other"], n="evaluated"))),
        ("U4", "Irregular errands not promoted", "real",
         f"{a['U4']['promoted']} of {a['U4']['irregular_places']} irregular places promoted",
         cal(lambda c: f"{c['U4']['promoted']} of {c['U4']['irregular_places']}")),
        ("U5", "Routine stops", "real",
         f"{a['U5']['stopped_routines']} routines stopped; {a['U5']['proposals_after_stop']} proposals after the stop "
         f"(max {a['U5']['max_days_proposed_after_stop']} days)",
         cal(lambda c: f"{c['U5']['stopped_routines']} stopped; {c['U5']['proposals_after_stop']} proposals after")),
        ("U6", "Holidays / winter break", "real",
         f"break days {_p(a['U6']['break_days'])} vs other {_p(a['U6']['other_days'])}",
         cal(lambda c: f"{_p(c['U6']['break_days'])} vs {_p(c['U6']['other_days'])}")),
        ("U7", "Return after >= 14 days", "real",
         f"{a['U7']['gaps']} gaps; proposals in 14 days after {_p(a['U7']['proposals_in_14d_after'])}",
         cal(lambda c: _p(c["U7"]["proposals_in_14d_after"]))),
        ("U8", "Two drivers, routines scoped", "composed", f"{h.get('U8_leaks', '-')} leaks in {hhn} households",
         str(ch.get("U8_leaks", "-"))),
        ("U9", "Other driver does A's trip", "composed",
         f"B confirmed A's routine in {h.get('U9_b_confirmed_a_routine', '-')}/{hhn}",
         str(ch.get("U9_b_confirmed_a_routine", "-"))),
        ("U10", "Cross-person Q&A leakage", "composed + synthetic", f"QA {h.get('qa_by_uc', {}).get('U10', '-')}",
         ch.get("qa_by_uc", {}).get("U10", "-")),
        ("U11", "Guest / valet wiped", "synthetic on real route", f"residue {h.get('U11_guest_residue', '-')}",
         str(ch.get("U11_guest_residue", "-"))),
        ("U12", "'No' to a proposal", "real proposal + synthetic reply", u12(h), u12(ch) if ch else "-"),
        ("U13", "'Forget that', person keeps going", "real trips + synthetic forget", u13(h), u13(ch) if ch else "-"),
        ("U14", "Stated facts", "synthetic at real times", f"QA {h.get('qa_by_uc', {}).get('U14', '-')}",
         ch.get("qa_by_uc", {}).get("U14", "-")),
        ("U15", "Cabin temp by season", "real_context",
         f"proposal precision {settings(h, 'cabin_temp')}; missing seasonal facts: "
         f"{dict(h.get('missing_expected_facts', {})) or 'none'}", settings(ch, "cabin_temp")),
        ("U16", "Vehicle fault, two people", "synthetic", f"{h.get('U16_ok', '-')}/{hhn} promoted to vehicle",
         str(ch.get("U16_ok", "-"))),
        ("U17", "AC / heater vs outside temp", "real", _oracle17(o["U17"]), "carmem: unsupported"),
        ("U18", "Pre-warm / pre-cool", "real", _oracle18(o["U18"]), "carmem: unsupported"),
        ("U19", "Fan / defrost / seat heat", "real_context",
         f"fan proposal precision {settings(h, 'fan')}; seat and wheel heating: no carmem key",
         settings(ch, "fan")),
        ("U20", "Two drivers, different temps", "composed",
         f"{h.get('U20_wrong_person_values', '-')} proposals of the other person's value; QA "
         f"{h.get('qa_by_uc', {}).get('U20', '-')}", str(ch.get("U20_wrong_person_values", "-"))),
        ("U21", "Passenger dual zone", "synthetic", "not built: carmem has no passenger-scoped writes", "-"),
        ("U22", "Seat position per driver", "real_context", f"proposal precision {settings(h, 'seat_position')}",
         settings(ch, "seat_position")),
        ("U23", "Guest adjusts seat", "synthetic", f"owner seat intact {h.get('U23_ok', '-')}/{hhn}",
         str(ch.get("U23_ok", "-"))),
        ("U24", "Never adjust while moving", "real",
         f"{h.get('U24', {}).get('probes_with_act', '-')}/{h.get('U24', {}).get('probes', '-')} mid-trip probes "
         f"would act (carmem has no motion input)", "-"),
        ("U25", "Media by context", "real_context", f"proposal precision {settings(h, 'media')}",
         settings(ch, "media")),
        ("U26", "Volume / quiet hours", "synthetic", "not built: no carmem key", "-"),
        ("U27", "Saved places from routines", "real", "not built: carmem has no saved-place surface", "-"),
        ("U28", "Motorway vs local roads", "real", _oracle28(o["U28"]), "carmem: unsupported"),
        ("U29", "Departure heads-up timing", "real",
         f"median |error| of correct proposals: {a['U29']['median_of_car_medians_abs_minutes']} min",
         cal(lambda c: str(c["U29"]["median_of_car_medians_abs_minutes"]))),
        ("U30", "Charging habit (EV/PHEV)", "real", _oracle30(o["U30"]), "carmem: unsupported"),
        ("U31", "Refuelling habit", "synthetic", "not built: VED has no fuel level; no carmem key", "-"),
        ("U32", "Maintenance by mileage", "real mileage + synthetic", "not built: no carmem key", "-"),
        ("U33", "Tyre pressure warning", "synthetic", f"{h.get('U33_ok', '-')}/{hhn} promoted to vehicle",
         str(ch.get("U33_ok", "-"))),
        ("U34", "Drive mode / ADAS never auto", "synthetic", "not built: no carmem key", "-"),
        ("U35", "Child seat (sensitive)", "synthetic",
         f"sent to cloud unmasked in {h.get('U35_sent_to_cloud', '-')}/{hhn}", str(ch.get("U35_sent_to_cloud", "-"))),
    ]


def _oracle17(o: dict[str, Any]) -> str:
    ac, ht = o["ac"], o["heater"]
    return (f"AC: {ac['cars_evaluated']}/{ac['cars_with_data']} cars, a one-threshold model gets "
            f"{ac['mean_accuracy']} vs {ac['mean_baseline']} baseline; heater: {ht['cars_evaluated']}/"
            f"{ht['cars_with_data']} cars, {ht['mean_accuracy']} vs {ht['mean_baseline']}")


def _oracle18(o: dict[str, Any]) -> str:
    cars = o["cars"]
    hs = [c["heater_on_share"] for c in cars if c["heater_on_share"] is not None]
    acs = [c["ac_on_share"] for c in cars if c["ac_on_share"] is not None]
    f = lambda xs: f"{sum(xs) / len(xs):.2f} over {len(xs)} cars" if xs else "no data"  # noqa: E731
    return f"heater on in first 5 min on cold weekday mornings: {f(hs)}; AC on when > 25 C: {f(acs)}"


def _oracle28(o: dict[str, Any]) -> str:
    if "odd_even_week_correlation" not in o:
        return o.get("status", "n/a")
    return (f"{o['cars']} cars; motorway share odd vs even weeks r={o['odd_even_week_correlation']}, "
            f"label agreement {o['motorway_user_label_agreement']}")


def _oracle30(o: dict[str, Any]) -> str:
    cars = o["cars"]
    if not cars:
        return "no charging events found"
    on = sum(c["overnight_share"] for c in cars) / len(cars)
    return (f"{len(cars)} cars; charges at the training-weeks top place {o['mean_test_at_top_place']} of the time; "
            f"overnight {on:.2f}")


def write_report(path: Path, s: dict[str, Any], results: list[dict[str, Any]], profiles) -> None:
    a = s["aggregate"]
    cal = s.get("calibrated")
    lines = [
        "# Real-data benchmark results",
        "",
        "Generated by `python -m bench.real.run_real` from the Vehicle Energy Dataset (VED, Apache-2.0) "
        "following `docs/DATASET_USECASES.md`. Do not edit by hand; rerun to refresh.",
        "",
        "```",
        f"python -m bench.real.run_real --radius {s['radius_m']:g} --households {a.get('households', 0)} "
        f"--calibrated-ratio {cal['routine_confirm_min_ratio'] if cal else 0.5} "
        "--export fixtures/real --report docs/REAL_BENCH_RESULTS.md",
        "```",
        "",
        "## Data",
        "",
        f"* {s['trips']:,} real trips from {s['vehicles']} cars, Nov 2017 - Nov 2018, Ann Arbor MI.",
        f"* {a['solo_cars']} cars have >= 150 trips over >= 20 active weeks and are replayed one car, one driver.",
        f"* {a.get('households', 0)} two-driver households composed from pairs of those cars, with synthetic "
        "utterances, traps and settings placed at real trip times.",
        f"* Places: {s['places']} shared places at a {s['radius_m']:g} m radius (VED fogs trip ends; the head unit "
        "uses 150 m on clean GPS).",
        "* Times: VED's clock is UTC; converted to America/Detroit.",
        "",
        "## Confirm-ratio sweep (solo cars, routines only)",
        "",
        "Held-out: each routine confirmed at a 28-day checkpoint is checked against the next 56 days; it counts as "
        "true when it happens on at least half of the days the car was driven on that weekday. Proposals are scored "
        "against the trip the driver actually took.",
        "",
        "| confirm ratio | held-out precision | proposals | proposal precision | commute routines |",
        "|---|---|---|---|---|",
    ]
    for r in s.get("sweep", []):
        ho = r["heldout"]
        lines.append(f"| {r['routine_confirm_min_ratio']} | {_p(ho, n='evaluated')} | "
                     f"{r['proposals']['n']} | {_p(r['proposals'])} | "
                     f"{r['commute_confirmed']}/{r['cars_with_work']} |")
    lines += [
        "",
        "## Use cases",
        "",
        f"Default policy (confirm ratio {s['policy']['routine_confirm_min_ratio']})"
        + (f" and calibrated (confirm ratio {cal['routine_confirm_min_ratio']})." if cal else "."),
        "",
        "| # | Use case | Provenance | Default policy | Calibrated |",
        "|---|---|---|---|---|",
    ]
    for uc, name, prov, res, calres in _uc_rows(s):
        lines.append(f"| {uc} | {name} | {prov} | {res} | {calres} |")
    hh = [r for r in results if r["kind"] == "household"]
    if hh:
        lines += ["", "## Households", "", "| scenario | events by provenance | swapped trips | B trips dropped "
                  "(overlap) | traps planted on |", "|---|---|---|---|---|"]
        for r in hh:
            m = r["meta"]
            lines.append(f"| {r['name']} | {r['events_by_provenance']} | {m['swapped_trips']} | "
                         f"{m['b_trips_dropped_for_overlap']} | {m.get('traps_planted_on', {})} |")
    lines += [
        "",
        "## Caveats",
        "",
        "* VED logs only some of each car's trips (median car: about 2-3 logged trips a week), so routine "
        "ratios are lower than a head unit that sees every trip would measure. Held-out checks only count weekdays "
        "on which the car was logged.",
        "* Trip ends are fogged to major intersections; one real place can split into neighbouring clusters.",
        "* The U9 swap rate is an assumption (10%) until it is computed from NHTS 2022.",
        "* Households are composed: B's real trips are re-homed to A's home. Overlapping trips are dropped.",
        "* Settings values (temperature, seat, media, fan) are synthetic; the outside temperature and trip times "
        "that drive them are real.",
        "",
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines))
    print(f"wrote {path}")
