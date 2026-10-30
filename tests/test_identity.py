from datetime import datetime

from carmem import CarMemory, IdentitySignal


def make():
    mem = CarMemory()
    mem.enroll("asha", "Asha", [("key_fob", "fob-a"), ("phone", "asha-phone"), ("voice", "v-asha")])
    mem.enroll("ben", "Ben", [("phone", "ben-phone"), ("voice", "v-ben")])
    return mem


def test_hard_signal_identifies_driver():
    mem = make()
    occ = mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=datetime(2026, 9, 7, 8))
    assert occ.driver_id == "asha" and not occ.is_guest


def test_voice_alone_is_a_guest():
    mem = make()
    occ = mem.ignition_on([IdentitySignal("voice", "v-asha", 0.95)], now=datetime(2026, 9, 7, 8))
    assert occ.is_guest and occ.driver_id.startswith("guest:")


def test_voice_breaks_hard_signal_tie_only_among_candidates():
    mem = make()
    # Asha's fob, Ben's phone: conflicting hard signals; Ben's voice picks Ben.
    occ = mem.ignition_on(
        [IdentitySignal("key_fob", "fob-a"), IdentitySignal("phone", "ben-phone"), IdentitySignal("voice", "v-ben", 0.9)],
        now=datetime(2026, 9, 7, 8),
    )
    assert occ.driver_id == "ben"


def test_guest_memory_is_wiped_at_ignition_off():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    occ = mem.ignition_on([IdentitySignal("voice", "unknown", 0.5)], now=t)
    mem.hear("my home is 12 Elm Street", now=t)
    mem.hear("set it to 25 degrees", now=t)
    assert mem.store.facts([occ.driver_id])
    res = mem.ignition_off(now=t)
    assert occ.driver_id in res["wiped"]
    assert not mem.store.facts([occ.driver_id])
    assert not mem.store.episodes([occ.driver_id])
    assert not [s for s in mem.store.scopes_present() if s.startswith("guest:")]


def test_passenger_resolved_separately():
    mem = make()
    occ = mem.ignition_on(
        [IdentitySignal("key_fob", "fob-a"), IdentitySignal("phone", "ben-phone", role="passenger")],
        now=datetime(2026, 9, 7, 8),
    )
    assert occ.driver_id == "asha" and occ.passenger_ids == ["ben"]
