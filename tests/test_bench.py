from bench.run_bench import replay, score
from bench.synth import HOUSEHOLD, generate
from carmem import CarMemory


def test_benchmark_passes_its_own_bar():
    events, gt = generate(seed=7, weeks=8)
    mem = CarMemory()
    for pid, info in HOUSEHOLD.items():
        mem.enroll(pid, info["name"], info["signals"])
    log = replay(mem, events, gt)
    m = score(mem, log, gt, events)
    assert m["routine_recall"] == 1.0 and m["routine_precision"] == 1.0, m["confirmed_routines"]
    assert m["cross_person_leaks"] == []
    assert m["guest_scopes_remaining"] == [] and m["guest_strings_remaining"] == []
    assert m["forget"]["proposals_after_forget"] == 0 and not m["forget"]["routine_record_exists"]
    assert m["missing_expected_facts"] == []
    assert m["qa_accuracy"] == 1.0, m["qa"]
    assert m["cloud_masked_fields"] >= 2 and not m["cloud_packet_leaks_sensitive"]
    assert m["false_proactive_rate_by_tier"].get("suggest", 0) < 0.15
