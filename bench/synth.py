"""Generate a household's driving logs with ground truth and planted traps.

The point is not fact lookup. The log is built so that a memory system can be
scored on promotion precision, time-to-proactive, false proactive rate,
correction half-life, resurrection after 'forget', cross-person leakage and
guest wipe - the things that go wrong in a shared car.
"""

from __future__ import annotations

import random
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any

from carmem.models import Trip

HOUSEHOLD = {
    "asha": {"name": "Asha", "signals": [("key_fob", "fob-asha"), ("phone", "asha-phone"), ("voice", "v-asha")]},
    "ben": {"name": "Ben", "signals": [("phone", "ben-phone"), ("voice", "v-ben")]},
}


@dataclass
class Event:
    ts: datetime
    type: str  # trip | say | sensor
    driver: str  # person id, or "guest"
    trip: Trip | None = None
    text: str = ""
    payload: dict[str, Any] = field(default_factory=dict)
    # runner hooks: if a proposal names one of these destinations, say "no" / "forget that" first
    reject_if_proposed: list[str] = field(default_factory=list)
    forget_if_proposed: list[str] = field(default_factory=list)
    week: int = 0


@dataclass
class GroundTruth:
    """What the benchmark expects at the end of the log."""

    expected_confirmed: dict[str, set[str]]  # scope -> destinations that should be confirmed routines
    expected_absent: dict[str, set[str]]  # scope -> destinations that must NOT be routines in that scope
    expected_facts: dict[str, list[tuple[str, str, Any]]]  # scope -> (category, key, value-substring)
    forbidden_values: dict[str, list[str]]  # scope -> strings that must never appear in that scope's memory
    forgotten: dict[str, str]  # scope -> destination that was forgotten (resurrection check)
    corrected: dict[str, str]  # scope -> destination the driver keeps saying no to
    qa: list[dict[str, Any]]  # LongMemEval-style questions with expected / forbidden substrings
    guest_strings: list[str]  # strings that must be gone from the store after the guest session


def jitter(rng: random.Random, minutes: int = 6) -> timedelta:
    return timedelta(minutes=rng.randint(-minutes, minutes))


def generate(seed: int = 7, weeks: int = 8, start: datetime | None = None) -> tuple[list[Event], GroundTruth]:
    rng = random.Random(seed)
    start = start or datetime(2026, 8, 17)  # a Monday in late summer; autumn begins in week 3
    events: list[Event] = []

    def trip(ts: datetime, who: str, origin: str, dest: str, temp: float | None, minutes: int = 20, **hooks) -> Event:
        settings = {"cabin_temp": temp} if temp is not None else {}
        t = Trip(ts, ts + timedelta(minutes=minutes), origin, dest, [who] if who != "guest" else [], settings,
                 {"tolls": False}, distance_km=rng.uniform(3, 15))
        return Event(ts, "trip", who, trip=t, week=(ts - start).days // 7 + 1, **hooks)

    def say(ts: datetime, who: str, text: str) -> Event:
        return Event(ts, "say", who, text=text, week=(ts - start).days // 7 + 1)

    for w in range(weeks):
        week = w + 1
        for d in range(7):
            day = start + timedelta(weeks=w, days=d)
            month_season = "summer" if day.month in (6, 7, 8) else "autumn"
            asha_temp = 19.0 if month_season == "summer" else 21.0

            if d < 5:  # Asha's weekday pattern
                t1 = day.replace(hour=7, minute=40) + jitter(rng)
                events.append(trip(t1, "asha", "home", "Oakwood Primary", asha_temp, 15))
                t2 = t1 + timedelta(minutes=25) + jitter(rng, 3)
                events.append(trip(t2, "asha", "Oakwood Primary", "Riverside Office", asha_temp, 25))
                t3 = day.replace(hour=17, minute=30) + jitter(rng)
                events.append(trip(t3, "asha", "Riverside Office", "home", asha_temp, 30))
                if week == 1 and d == 0:
                    events.append(say(t1 + timedelta(minutes=2), "asha", "my kid's school is Oakwood Primary"))
                if week == 2 and d == 1:
                    events.append(say(t3 + timedelta(minutes=5), "asha", "I hate toll roads"))
                    events.append(say(t3 + timedelta(minutes=6), "asha", "the left rear sensor is flaky"))
                if week == 1 and d == 2:
                    events.append(say(t3 + timedelta(minutes=4), "asha", "I'm allergic to penicillin"))
            if d == 4:  # Friday gym; forgotten at week 5, but Asha keeps going (resurrection trap)
                tg = day.replace(hour=18, minute=30) + jitter(rng)
                hooks = {"forget_if_proposed": ["gym"]} if week == 5 else {}
                events.append(trip(tg, "asha", "home", "FitLife Gym", asha_temp, 15, **hooks))
            if d == 5 and rng.random() < 0.5:  # noisy Saturday errand, ~50%: must stay below confirm ratio
                te = day.replace(hour=11, minute=0) + jitter(rng, 20)
                events.append(trip(te, "asha", "home", "Supermarket", asha_temp, 12))

            if d in (1, 3):  # Ben's yoga
                ty = day.replace(hour=18, minute=0) + jitter(rng)
                events.append(trip(ty, "ben", "home", "Lotus Yoga", 23.0, 15))
                if week == 4 and d == 1:
                    events.append(say(ty + timedelta(minutes=3), "ben", "the left rear sensor keeps beeping at me"))
            if d == 5:  # Ben's Saturday: market for 4 weeks, then hardware store and he says no to market
                tm = day.replace(hour=10, minute=0) + jitter(rng)
                if week <= 4:
                    events.append(trip(tm, "ben", "home", "Farmers Market", 23.0, 20))
                else:
                    events.append(trip(tm, "ben", "home", "Hardware Store", 23.0, 20, reject_if_proposed=["Farmers Market"]))

            if week == 3 and d == 2:  # a sensor event
                events.append(Event(day.replace(hour=12), "sensor", "vehicle",
                                    payload={"key": "left_rear_sensor", "value": "intermittent fault code B1234"}, week=week))
            if week == 5 and d == 2:  # a guest (valet) drives in the evening, voice only
                tgst = day.replace(hour=20, minute=15)
                events.append(say(tgst, "guest", "my home is 12 Elm Street"))
                events.append(say(tgst + timedelta(minutes=1), "guest", "set it to 25 degrees"))
                events.append(trip(tgst + timedelta(minutes=2), "guest", "Riverside Office", "12 Elm Street", 25.0, 18))

    events.sort(key=lambda e: e.ts)

    gt = GroundTruth(
        expected_confirmed={
            "asha": {"Oakwood Primary", "Riverside Office", "home"},
            "ben": {"Lotus Yoga", "Hardware Store"},
        },
        expected_absent={
            "asha": {"Lotus Yoga", "Farmers Market", "Hardware Store", "12 Elm Street", "Supermarket"},
            "ben": {"Oakwood Primary", "Riverside Office", "FitLife Gym", "12 Elm Street"},
        },
        expected_facts={
            "asha": [("school", "school_location", "Oakwood"), ("route_prefs", "avoid_tolls", "True"),
                     ("climate", "cabin_temp", "21"), ("climate", "cabin_temp", "19"), ("health", "allergy", "penicillin")],
            "ben": [("climate", "cabin_temp", "23")],
            "vehicle": [("vehicle_fault", "left_rear_sensor", "")],
        },
        forbidden_values={"asha": ["Elm", "Lotus", "Farmers"], "ben": ["Oakwood", "penicillin", "Elm", "FitLife"]},
        forgotten={"asha": "FitLife Gym"},
        corrected={"ben": "Farmers Market"},
        qa=[
            {"who": "asha", "q": "where is my kid's school", "expect": ["Oakwood"], "forbid": []},
            {"who": "asha", "q": "what temperature do I like", "expect": ["cabin_temp = 21"], "forbid": ["cabin_temp = 23"]},
            {"who": "asha", "q": "do I like toll roads", "expect": ["avoid_tolls = True"], "forbid": []},
            {"who": "asha", "q": "what is wrong with the car", "expect": ["left rear sensor"], "forbid": []},
            {"who": "asha", "q": "when do I go to yoga", "expect": [], "forbid": ["Lotus"]},
            {"who": "ben", "q": "where is my kid's school", "expect": [], "forbid": ["Oakwood"]},
            {"who": "ben", "q": "what am I allergic to", "expect": [], "forbid": ["penicillin"]},
            {"who": "ben", "q": "what temperature do I like", "expect": ["cabin_temp = 23"],
             "forbid": ["cabin_temp = 21", "cabin_temp = 19"]},
            {"who": "ben", "q": "what is wrong with the car", "expect": ["left rear sensor"], "forbid": []},
        ],
        guest_strings=["Elm"],
    )
    return events, gt
