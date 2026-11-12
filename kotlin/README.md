# carmem (Kotlin)

Three modules:

* `carmem-core`: the memory itself. Pure JVM. Same behaviour as the Python reference (see Conformance), with its own
  storage layer and the head-unit features listed under "Ahead of the Python reference".
* `carmem-car`: car events, trip segmentation, place resolution and session orchestration. Pure JVM, tested.
* `carmem-android`: Android Automotive bound service and platform adapters. Built only when an Android SDK is configured.

```
cd kotlin
./gradlew test                     # core + car, 36 tests
./gradlew :carmem-core:latency     # per-call latency table, see below
./gradlew :carmem-android:assembleDebug   # with ANDROID_HOME set
```

## What it remembers

One test per row in `UseCaseTest` (core) and `CarTest` (through the car pipeline).

| Kind | Example | Lifetime | How |
|---|---|---|---|
| Conversation | "take me there", "call him" | five minutes or the drive; RAM only | `hear` records the driver's turn, `said` the assistant's, `mention` what was resolved; `resolve(text)` and the "In focus" line of the context say what a pronoun means |
| Live vehicle state | location, destination, cabin settings, who is seated | not stored | `CarSession` publishes it with `setLive`; the context packet shows it under "Right now" |
| Preferences | "21°C", "avoid tolls", "likes jazz" | long-term | stated, or learned from three consistent observations of cabin temperature, fan or what is playing; a dislike replaces a like |
| Named things | "mom's house", "the boss" = John | long-term | "the boss is John"; "mom's house is at 12 Elm Street"; "this is mom's house" or `namePlace` names where the car is |
| Routines | gym Tuesdays 7 pm | long-term, decays | mined from trips; demoted by "no", retired when it stops, blocked by "forget that" |
| Past events | "the restaurant from last Friday" | six weeks | trips and utterances; "last Friday", "yesterday evening", "two days ago" restrict recall to that time; a place gets its name and kind when navigation is set to it (`setDestination`) |

And what a shared car adds:

| Problem | Handling |
|---|---|
| Several drivers | memory is per person: Android user profile, paired phone, and key fob or face when a component reports them (`observeOccupant`) |
| Passengers speaking | `hear(text, speakerId)`: a known passenger's words go to their memory; a speaker the car cannot identify gets a scope that ends with the drive |
| Rental and fleet cars | `Policy.guest_only`: everyone is a guest, nobody is enrolled, nothing outlives the trip |
| Resale | `eraseAll()` empties every table and the place list; `erasePerson(id)` removes one person |
| Stays on the device | no network port; the cloud packet carries placeholders (`<contacts_2>`, `<place_1>`, `<note_3>`) for sensitive facts, notes, and every named place, and `unmask` puts the values back |
| View and delete | `export(id)` returns everything held about a person; `forget` removes a fact with its earlier values, the utterances it came from and its audit trail |
| Retention | conversation 5 minutes, episodes 6 weeks, audit trail 90 days, retired facts and routines 180 days, forgotten-item blocks 90 days; all in `Policy` |

Limits worth knowing: a toll preference is stated, not learned (no platform signal says a road was tolled); a place has a
kind only when navigation or the driver gave it one, the car does not look places up; encryption at rest is Android's
per-user file encryption; and the conversation and episode text sent in a cloud packet is masked only where it repeats a
value the memory holds, so what else the driver said is the assistant's to route.

## Latency

The budget is 50 ms per call as the assistant sees it. `./gradlew :carmem-core:latency` replays six months of a
two-driver household (about 1,100 trips and 3,400 utterances; 1,047 episodes inside the retention window) into a
file-backed database and times every call 200 times. On a cloud build machine (JDK 21):

| call | p50 | p99 | p99 before this work |
|---|---|---|---|
| `context("")` | 0.3 ms | 0.7 ms | 4.0 ms |
| `context(query)` / `recall` | 1.4 ms | 3-5 ms | 23-25 ms |
| `proactive` | 0.6 ms | 1 ms | 9.3 ms |
| `hear`, nothing to store | 0.1 ms | 0.4 ms | 2.9 ms |
| `hear`, fact or note stored | 1.4 ms | 4-5 ms | 14.5-14.9 ms |
| `recordTrip` (not a client call) | 3.7 ms | 11 ms | 17.9 ms |
| ignition off + on (not a client call) | 9 ms | 13 ms | 122 ms |
| first `context(query)` after a restart | 6-10 ms once | | |

Figures move by a millisecond or so between runs. The run also shows one 90 ms `recordTrip`: the benchmark makes
about 1,500 writes without an ignition off, which reaches the 64 MB backstop on the write-ahead log. A car that is
switched off between drives does not get there.

A head unit is slower than a build machine, by an amount that has to be measured on the unit. The service times
every call itself: `ICarMemory.stats()` returns count, p50/p95/p99/max, time spent queued and the number of calls
over 50 ms, and any call over budget is logged under tag `carmem`. What keeps the numbers low:

* One transaction per call, a write-ahead log with `synchronous=NORMAL`, and the log folded back into the database
  at ignition off rather than inside whichever call crosses SQLite's threshold (a 20 ms stall here; more on flash).
* Episode vectors are float64 blobs, ranked from an in-memory cache; payloads are parsed only for the episodes returned.
* A driver's facts and routines are cached until the store says one of them changed.
* Evidence lists are cut to the policy window (plus the last ten for `explain`), so a read costs the same in year
  two as in week two.

Two things sit outside these numbers. With an `LlmClient` configured, `hear` waits for the model: an assistant that
has its own model should leave carmem's unset (no model file, no key file) so extraction stays rule-based, and send
what its model wants kept through `memory_remember`. And a client call queues behind whatever the service's single
thread is doing; `queued_p99_ms` in `stats()` shows how much of a slow call was that.

## Ahead of the Python reference

The Python package remains the reference for consolidation behaviour. These exist only here, because they are about
running on a head unit:

* `CarSession` enrols the driver's Android user profile the first time it is seen (`autoEnrollProfiles`, on by
  default). Without it nobody is enrolled, every driver is a guest, and guests form no memory.
* `CarMemory.renamePlace`: when a place gets a name (the car infers home or work, or the driver names it), its
  routines, evidence, tombstones and trip history move to the new name.
* Places are created where trips start and end, not for fixes on the road, and are stored in the database after
  every trip.
* `proactive(..., moving)` and `Policy.act_blocked_while_moving` (default `["seat"]`): no seat change while moving.
* `memory_remember` keeps text as a note when no extraction pattern matches, instead of dropping it.
* Transactions, schema version, `LatencyRecorder`.
* Everything under "What it remembers" beyond routines, stated facts and cabin temperature: conversation, live state,
  contacts and place names, dated recall, passenger scopes, `guest_only`, export, erase, and the stricter cloud masking.

## Conformance

`ConformanceTest` replays `../fixtures/household_seed7_w8.json`, exported by the
Python reference with `python -m bench.export_fixtures`, using the protocol in
`../fixtures/wire-schema.json`, and asserts the same outcomes: the exact sequence of
90 proactive proposals with tiers, confirmed routines and facts per person, the "no"
counts, the tombstone left by "forget that", zero cross-person or guest leakage, every
QA item, and the cloud-masked field count. Change the policy or the consolidation
rules in Python, re-export, and this test tells you whether the port still agrees.

## Layout

| File | Mirrors |
|---|---|
| `Models.kt` | `carmem/models.py` |
| `Policy.kt` | `carmem/policy.py` (serialisable, loads the fixture's policy JSON) |
| `SqlDriver.kt` | the only database surface; `JdbcSqlDriver` for JVM, AndroidX bundled SQLite on device |
| `Store.kt` | `carmem/store.py` |
| `Identity.kt` | `carmem/identity.py` |
| `Extraction.kt` | `carmem/extraction.py` plus the `LlmClient` interface |
| `Reconcile.kt` | `carmem/reconcile.py` |
| `Consolidation.kt` | `carmem/consolidation.py` |
| `Context.kt` | `carmem/context.py` |
| `CarMemory.kt` | `carmem/memory.py` |
| `Tools.kt` | `carmem/llm/tools.py`, neutral + Anthropic + OpenAI shapes |

## Plugging in a model on device

Implement `LlmClient.completeJson(system, user, schemaJson)` over LiteRT-LM with
JSON-schema constrained decoding and pass it to `CarMemory`. Extraction and note
reconciliation then use the model; driving-time intents stay rule-based. Expose
`CarMemory.toolSpecs()` to the on-device assistant as a `ToolSet` and route calls to
`handleTool`. For the cloud agent, send `context(query, "cloud").render()` with the
request, execute its tool calls locally with `handleTool`, and `unmask` the reply.
