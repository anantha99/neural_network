from datetime import datetime, timedelta

from carmem import CarMemory, IdentitySignal, Trip
from carmem.llm import to_anthropic_tools, to_openai_tools
from carmem.llm.base import ScriptedLLM


def make():
    mem = CarMemory()
    mem.enroll("asha", "Asha", [("key_fob", "fob-a")])
    mem.enroll("ben", "Ben", [("phone", "ben-phone")])
    return mem


def test_cloud_context_masks_sensitive_and_unmasks_reply():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    mem.hear("my kid's school is Oakwood Primary", now=t)
    mem.hear("I'm allergic to penicillin", now=t)
    mem.hear("I prefer 21 degrees", now=t)
    local = mem.context("school", "local", now=t).render()
    cloud_pkt = mem.context("school", "cloud", now=t)
    cloud = cloud_pkt.render()
    assert "Oakwood" in local and "Oakwood" not in cloud and "penicillin" not in cloud
    assert "21" in cloud  # climate is public
    token = next(k for k, v in cloud_pkt.masked.items() if v == "Oakwood Primary")
    assert cloud_pkt.unmask(f"Heading to {token} now") == "Heading to Oakwood Primary now"


def test_context_is_scoped_to_current_driver():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    mem.hear("my kid's school is Oakwood Primary", now=t)
    mem.ignition_off(now=t)
    mem.ignition_on([IdentitySignal("phone", "ben-phone")], now=t + timedelta(hours=1))
    assert "Oakwood" not in mem.context("school", now=t + timedelta(hours=1)).render()


def test_recall_finds_episodes():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    mem.record_trip(Trip(t, t + timedelta(minutes=30), "home", "Airport Terminal 2", ["asha"], {}, {"tolls": True}))
    out = mem.recall("airport", now=t + timedelta(hours=1))
    assert any("Airport" in e["text"] for e in out["episodes"])


def test_tool_dispatch_and_formats():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    mem.handle_tool("memory_remember", {"text": "I hate toll roads"})
    out = mem.handle_tool("memory_recall", {"query": "tolls"})
    assert any("avoid_tolls" in f["text"] for f in out["facts"])
    assert mem.handle_tool("memory_proactive")["proposals"] == []
    names = {t_["name"] for t_ in to_anthropic_tools()}
    assert "memory_recall" in names and all("input_schema" in t_ for t_ in to_anthropic_tools())
    assert all(t_["type"] == "function" for t_ in to_openai_tools())


def test_llm_extractor_uses_model_output_and_falls_back():
    scripted = ScriptedLLM({
        "lunch": {"candidates": [{"category": "note", "key": "lunch_spot", "value": "Nando's", "condition": None,
                                   "kind": "stated", "about": "person"}]},
    })
    mem = CarMemory(llm=scripted)
    mem.enroll("asha", "Asha", [("key_fob", "fob-a")])
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    r = mem.hear("my usual lunch is at Nando's", now=t)
    assert r["stored"] and r["stored"][0]["fact"].startswith("note/lunch_spot")


def test_snapshot_for_cloud_drops_masked_categories():
    mem = make()
    t = datetime(2026, 9, 7, 8)
    mem.ignition_on([IdentitySignal("key_fob", "fob-a")], now=t)
    mem.hear("I'm allergic to penicillin", now=t)
    mem.hear("I prefer 21 degrees", now=t)
    assert len(mem.snapshot()["facts"]) == 2
    cloud = mem.snapshot(for_cloud=True)["facts"]
    assert len(cloud) == 1 and "climate" in cloud[0]["fact"]
