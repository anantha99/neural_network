"""Bugs found by the real-data benchmark (bench/real)."""

from datetime import datetime, timedelta

from carmem import CarMemory, IdentitySignal, Trip

FOB = [IdentitySignal("key_fob", "f")]


def _drive(mem, start, origin, dest):
    mem.ignition_on(FOB, now=start)
    mem.record_trip(Trip(start, start + timedelta(minutes=20), origin, dest, ["a"]), now=start + timedelta(minutes=20))
    mem.ignition_off(now=start + timedelta(minutes=25))


def test_forget_by_destination_does_not_remove_a_routine_that_starts_there():
    # Real driver: work -> gym on Mondays was mined before home -> work on Tuesdays.
    mem = CarMemory()
    mem.enroll("a", "A", [("key_fob", "f")])
    t = datetime(2018, 1, 1)  # Monday
    for w in range(4):
        _drive(mem, t + timedelta(weeks=w, hours=17), "work", "gym")
        _drive(mem, t + timedelta(weeks=w, days=1, hours=8), "home", "work")
    mem.ignition_on(FOB, now=t + timedelta(weeks=5))
    r = mem.hear("forget that I go to work", now=t + timedelta(weeks=5))
    assert r["forget"]["description"].endswith("-> work"), r["forget"]
    left = {x.destination for x in mem.store.routines(["a"])}
    assert "gym" in left and "work" not in left


def test_child_details_are_sensitive_and_never_reach_the_cloud():
    mem = CarMemory()
    mem.enroll("a", "A", [("key_fob", "f")])
    now = datetime(2018, 1, 1, 8)
    mem.ignition_on(FOB, now=now)
    stored = mem.hear("remember that there is a child seat in the back", now=now)["stored"]
    assert stored and stored[0]["fact"].startswith("children/")
    local = mem.context("child seat", "local", now=now).render()
    cloud = mem.context("child seat", "cloud", now=now)
    assert "child seat" in local
    assert "child seat" not in cloud.render() and cloud.masked
