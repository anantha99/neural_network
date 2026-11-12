# carmem — memory for in-car LLM assistants

[![CI](https://github.com/anantha99/neural_network/actions/workflows/ci.yml/badge.svg)](https://github.com/anantha99/neural_network/actions/workflows/ci.yml)
MIT licensed. Status: 0.1, the Python reference and Kotlin core are complete; the Android Automotive module is experimental.

`carmem` is an on-device, identity-scoped memory layer for assistants that live in a
shared car. It is written for the ways a car differs from a chat app:

* **The device is shared.** Identity is the first-class key. Every record is scoped to a
  person; a guest gets an ephemeral scope that is wiped at ignition off. Cross-person
  leakage is treated as a correctness bug, and the benchmark tests for it.
* **The car is a sensor stream.** Most useful memory is implicit: routes, times, climate
  settings. Routines are *mined* from trips, not stated, and a stated fact and a mined
  pattern never share a promotion threshold.
* **Recall happens under driving workload.** Proactive actions are gated by tiers
  (silent / suggest / act / never) that encode the cost of being wrong at 80 km/h, and a
  one-word "no" corrects the record.
* **The car is offline often.** The store is a single SQLite file on the head unit. Cloud is
  a sync target; sensitive fields are masked before they travel.

It works with any LLM. The core has no dependencies; a model is optional and plugs in
behind a two-method interface.

```
pip install -e .            # core, zero dependencies
pip install -e .[anthropic] # optional Claude adapter
pip install -e .[dev]       # pytest
```

## Five layers, one write policy each

| Layer | What it holds | Written by | Promotion rule |
|---|---|---|---|
| Identity | who is present now | key fob, phone, seat profile; voice is soft | no hard signal → guest, wiped at ignition off |
| Episodic | trips and utterances, append-only | every event | retained ~6 weeks, never leaves the car |
| Semantic | facts and preferences with provenance | extraction + observed settings | stated → confirmed now; observed → 3 consistent observations |
| Procedural | routines the car may act on | mined from trips | 3 occurrences, 3 distinct weeks, ≥70% of slots, no recent contradiction |
| Vehicle | facts about the car, shared by all | sensors, or two people agreeing | sensor → confirmed; one person's claim stays theirs |

Every threshold and tier is data in `carmem.policy.Policy`. `carmem policy` prints it as
JSON; edit a number and pass the file back with `--policy`.

## Sixty-second tour

```python
from datetime import datetime, timedelta
from carmem import CarMemory, IdentitySignal, Trip

mem = CarMemory("car.db")
mem.enroll("asha", "Asha", [("key_fob", "fob-1"), ("phone", "asha-phone")])

t = datetime(2026, 9, 7, 7, 40)
mem.ignition_on([IdentitySignal("key_fob", "fob-1")], now=t)
mem.hear("my kid's school is Oakwood Primary", now=t)        # stated → confirmed, sensitive
mem.record_trip(Trip(t, t + timedelta(minutes=15), "home", "Oakwood Primary",
                     ["asha"], {"cabin_temp": 21}))          # implicit climate + routine evidence
mem.ignition_off(now=t + timedelta(hours=1))

# ...three weeks later, Monday 07:38, parked at home:
props = mem.proactive(now=t + timedelta(weeks=3), location="home")
# [suggest] Heading to Oakwood Primary? I've queued the route; say no to skip.
# [act]     Setting cabin temp to 21.
mem.hear("no")                 # demotes after two in a row, suppressed for this trip
mem.hear("forget that")        # deletes + tombstones so it cannot be re-mined for 90 days
mem.explain(props[0].record_id)  # the trips behind the suggestion
```

## Plugging into your assistant

Three surfaces, use whichever fits your loop:

1. **Context injection.** `mem.context(query, destination="cloud").render()` returns a block
   for the system prompt. For `destination="cloud"` the policy's sensitive fields are
   replaced by stable tokens; `packet.unmask(reply)` restores them before text-to-speech.
2. **Tools.** `mem.tool_specs("anthropic")` or `mem.tool_specs("openai")` gives tool
   definitions; `mem.handle_tool(name, args)` dispatches them. Tools: recall, remember,
   forget, correct, explain, proactive.
3. **Python API.** Everything above, plus `record_trip`, `record_vehicle_event`,
   `snapshot(for_cloud=True)` for sync.

Extraction uses deterministic rules by default. Pass `llm=AnthropicLLM()` or
`llm=OpenAICompatibleLLM(model=..., base_url=...)` to use a model for extraction; driving-time
intents (`no`, `yes`, `forget that`, `why`) stay rule-based so they work with no network.
See `examples/assistant_loop.py` for a complete provider-agnostic loop.

## Benchmark

`python -m bench.run_bench --seed 7 --weeks 8` generates a two-adult household plus a
guest, replays eight weeks of trips and utterances through the memory, and scores the
things that go wrong in shared cars rather than fact lookup:

```
routine precision / recall             1.0 / 1.0
false proactive rate by tier           {'suggest': 0.011}
days to first correct proactive        {'asha:Oakwood Primary': 21, ... 'ben:Hardware Store': 54}
correction (no's said, suggestions after first no, still confirmed)  (1, 0, False)
forget (week, proposals after, record exists, tombstoned)            (5, 0, False, True)
cross-person leaks                     none
guest scopes / strings remaining       ([], [])
climate condition split                [19.0 when season=summer, 21.0 when season=autumn]
QA accuracy                            1.0
cloud: masked fields / sensitive leak  (2, False)
```

Planted traps: a 50% Saturday errand that must stay below the confirm ratio, chained trips
25 minutes apart, a routine the driver forgets but keeps doing, a routine the driver
abandons and says "no" to, a valet with a voice-only identity, one person's school and
allergy that must never surface for the other.

### On real driving

`python -m bench.real.run_real` replays real trips from the Vehicle Energy Dataset (383
cars in Ann Arbor, Nov 2017 - Nov 2018, Apache-2.0) instead of generated ones: 74 cars one
driver at a time, plus two-driver households composed from pairs of real cars with the
utterances and traps above placed at real trip times. Every event is labelled `real`,
`composed`, `real_context` or `synthetic`, and routines are scored on whether they come true
in the following weeks. Use cases and method: `docs/DATASET_USECASES.md`; full results:
`docs/REAL_BENCH_RESULTS.md`.

Headline numbers on real trips, default policy:

```
proposal precision (routine proposed vs trip actually taken)   0.95  (n=61)
held-out routine precision (confirmed at day 28, true in next 56 days)   0.29  (n=126)
irregular places wrongly promoted                              0 of 391
proposals after a routine stopped                              3 of 80 stopped routines
cross-person leaks across 6 composed households                0
"forget that": right routine removed, re-mined inside tombstone   6/6, 0
child seat / school sent to cloud unmasked                      0/6
```

Read the two precision numbers together. Proposals are right 95% of the time when the car
is at a routine's origin in its slot. Held-out precision is low because VED logs only a
sample of each car's trips, a median of two to three a week, so a routine that is real
often does not reach the ratio threshold in the logged subset and a confirmed one is often
unobserved in the check window. A head unit that sees every trip will score higher; the
synthetic household above is the upper bound. Lowering the confirm ratio to 0.5 doubles the
commute routines found and costs ten points of proposal precision; the policy is a file.

Known limitations the real benchmark surfaced, none of them hidden by the numbers above:

* The Python reference has no motion input, so an act-tier setting could change mid-trip (U24).
  The Kotlin core takes the car's motion and withholds seat changes while moving.
* No passenger-scoped writes: a passenger's utterance is attributed, but seat occupancy
  does not open a scope (U21).
* No keys for charging, refuelling, maintenance, drive mode, volume or quiet hours, and no
  saved-place surface (U26, U27, U30 to U34).
* Fan proposals are poor (precision 0.19) because fan level depends on outside temperature,
  which the policy does not condition on yet (U19).

## Kotlin core and conformance fixtures

`kotlin/carmem-core` is the head-unit implementation for Android Automotive OS: pure
Kotlin/JVM with the database behind a four-method `SqlDriver` interface, so the same core
runs on the AndroidX bundled SQLite driver on device and on JDBC in CI. `fixtures/` holds
the wire schema and a replayable event log exported from the Python benchmark with
`python -m bench.export_fixtures`; the Kotlin `ConformanceTest` replays it and must
reproduce the same proposals, routines, facts, tombstones and QA results. The Python
package stays the reference and the research harness; the policy is tuned here first and
re-exported.

```
cd kotlin && ./gradlew test      # carmem-core + carmem-car on the JVM; needs JDK 21
```

## Android Automotive module

`kotlin/carmem-car` holds everything the head unit needs that is not platform code:
`CarEvent`s (ignition, location fix, setting changed, occupant observed, utterance,
vehicle fault), a `TripSegmenter` that turns fixes into trips and splits on dwell with the
engine running, a `PlaceResolver` that clusters coordinates into places and infers home
and work from dwell, and a `CarSession` that drives `CarMemory` from the event stream.
It is tested end to end on the JVM. `kotlin/carmem-android` is the thin Android library:
a foreground bound service exposing `ICarMemory` over AIDL with wire-schema JSON, a
`SqlDriver` over the AndroidX bundled SQLite driver, and adapters for vehicle properties,
occupant zones, location and Bluetooth. It is included in the Gradle build only when an
Android SDK is configured, and it is experimental: it runs on the AAOS 15 emulator, has not
been run on a vehicle, and relies on file-based encryption only. See `kotlin/carmem-android/README.md` for permissions and
deployment notes.

## Layout

```
carmem/
  identity.py       who is present; guest default
  store.py          SQLite schema and queries
  extraction.py     utterances → candidates; rule and LLM extractors; intents
  consolidation.py  the state machine: promotion, contradictions, splits, tiers, tombstones
  context.py        context packet, masking, un-masking
  memory.py         CarMemory facade and session state
  policy.py         every threshold and tier, as editable JSON
  llm/              LLMClient protocol, Anthropic and OpenAI-compatible adapters, tool specs
  wire.py           JSON schema shared by every implementation and client
  reconcile.py      Mem0-style add / update / noop for free-text memories
bench/              synthetic household generator, scorer, fixture exporter
bench/real/         real-data track: VED trips, composed households, per-use-case scoring
data/ved/           VED trip table (derived, Apache-2.0)
fixtures/           wire-schema.json and the replayable conformance fixture
kotlin/carmem-core  Kotlin port with conformance + unit tests (see kotlin/README.md)
kotlin/carmem-car   trip segmentation, places, session orchestration (JVM, tested)
kotlin/carmem-android  AAOS service, AIDL, SQLite driver, signal adapters (needs SDK)
tests/              pytest suite (python -m pytest)
.github/workflows/  CI: pytest + synthetic benchmark on 3.10 and 3.12, Kotlin JVM tests on JDK 21
docs/DESIGN.md      the design in detail
docs/HANDOFF.md     state of every piece, build steps, decisions, what to do next
```

## Contributing and license

Run `python -m pytest -q` and `cd kotlin && ./gradlew test` before opening a pull request.
A change to the policy or the consolidation rules must re-export the fixtures
(`python -m bench.export_fixtures`) so the Kotlin conformance test stays in step. Issues
with a failing replay or a real-trip case the miner gets wrong are the most useful kind.

carmem is released under the MIT license (`LICENSE`). The derived VED trip table in
`data/ved/` is Apache-2.0 from its authors; the NHTS figures in `data/nhts/` are public domain.

The two notebooks at the root (`forward_propogation.ipynb`, `neural_network_using_sklearn.ipynb`)
predate this project and are kept as-is.
