"""A provider-agnostic assistant loop wired to carmem.

Run offline:   python examples/assistant_loop.py
With Claude:   pip install 'carmem[anthropic]' && ANTHROPIC_API_KEY=... python examples/assistant_loop.py --claude

The pattern is the same for any model: build the context packet for the right audience,
put it in the system prompt, hand the model carmem's tools, dispatch tool calls to
``mem.handle_tool``, and un-mask the reply before speaking it.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime, timedelta

from carmem import CarMemory, IdentitySignal, Trip


def seed(mem: CarMemory) -> datetime:
    mem.enroll("asha", "Asha", [("key_fob", "fob-1")])
    t0 = datetime(2026, 9, 7, 7, 40)
    for w in range(4):
        for d in range(5):
            t = t0 + timedelta(weeks=w, days=d)
            mem.ignition_on([IdentitySignal("key_fob", "fob-1")], now=t)
            if w == 0 and d == 0:
                mem.hear("my kid's school is Oakwood Primary", now=t)
                mem.hear("I'm allergic to penicillin", now=t)
            mem.record_trip(Trip(t, t + timedelta(minutes=15), "home", "Oakwood Primary", ["asha"], {"cabin_temp": 21}))
            mem.ignition_off(now=t + timedelta(hours=1))
    now = t0 + timedelta(weeks=4)
    mem.ignition_on([IdentitySignal("key_fob", "fob-1")], now=now)
    return now


def answer_offline(mem: CarMemory, question: str, now: datetime) -> str:
    """Stand-in for a model: uses the tools the way a model would."""
    hits = mem.handle_tool("memory_recall", {"query": question, "k": 3})
    facts = [f["text"] for f in hits["facts"]]
    return "From memory: " + ("; ".join(facts[:2]) if facts else "nothing relevant.")


def answer_with_claude(mem: CarMemory, question: str, system: str) -> str:
    import anthropic

    client = anthropic.Anthropic()
    tools = mem.tool_specs("anthropic")
    messages = [{"role": "user", "content": question}]
    while True:
        resp = client.beta.messages.create(
            model="claude-opus-5-5", max_tokens=2048, system=system, tools=tools, messages=messages,
            betas=["server-side-fallback-2026-07-01"], fallbacks="default",
        )
        if resp.stop_reason == "refusal":
            return "I can't help with that one."
        messages.append({"role": "assistant", "content": resp.content})
        if resp.stop_reason != "tool_use":
            return "".join(b.text for b in resp.content if b.type == "text")
        results = []
        for block in resp.content:
            if block.type == "tool_use":
                out = mem.handle_tool(block.name, json.loads(json.dumps(block.input)))
                results.append({"type": "tool_result", "tool_use_id": block.id, "content": json.dumps(out, default=str)})
        messages.append({"role": "user", "content": results})


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--claude", action="store_true", help="use the Anthropic SDK instead of the offline stand-in")
    ap.add_argument("--cloud", action="store_true", help="render the packet for a cloud audience (masked)")
    args = ap.parse_args()

    mem = CarMemory()
    now = seed(mem)
    question = "Where does my kid go to school, and what should I expect this morning?"

    packet = mem.context(question, destination="cloud" if args.cloud or args.claude else "local", now=now)
    system = (
        "You are the in-car assistant. The driver is busy; answer in one sentence. "
        "Use the memory tools when the answer depends on history.\n\n" + packet.render()
    )
    print("--- system prompt memory block ---\n" + packet.render() + "\n")
    for p in mem.proactive(now=now, location="home"):
        print(f"[proactive/{p.tier.value}] {p.text}")

    reply = answer_with_claude(mem, question, system) if args.claude else answer_offline(mem, question, now)
    print("\nmodel said:   ", reply)
    print("driver hears: ", packet.unmask(reply))


if __name__ == "__main__":
    main()
