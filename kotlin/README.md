# carmem (Kotlin)

Three modules:

* `carmem-core`: the memory itself. Pure JVM. Same behaviour as the Python reference (see Conformance), with its own
  storage layer and the head-unit features listed under "Ahead of the Python reference".
* `carmem-car`: car events, trip segmentation, place resolution and session orchestration. Pure JVM, tested.
* `carmem-android`: Android Automotive bound service and platform adapters. Built only when an Android SDK is configured.

```
cd kotlin
./gradlew test                     # core + car, 22 tests
./gradlew :carmem-core:latency     # per-call latency table, see below
./gradlew :carmem-android:assembleDebug   # with ANDROID_HOME set
```

## Latency

The budget is 50 ms per call as the assistant sees it. `./gradlew :carmem-core:latency` replays six months of a
two-driver household (about 1,100 trips and 3,400 utterances; 1,047 episodes inside the retention window) into a
file-backed database and times every call 200 times. On a cloud build machine (JDK 21):

| call | p50 | p99 | p99 before this work |
|---|---|---|---|
| `context("")` | 0.2 ms | 0.4 ms | 4.0 ms |
| `context(query)` / `recall` | 1.3 ms | 4-5 ms | 23-25 ms |
| `proactive` | 0.5 ms | 2 ms | 9.3 ms |
| `hear`, nothing to store | 0.05 ms | 0.4 ms | 2.9 ms |
| `hear`, fact or note stored | 0.5 ms | 1.1-1.4 ms | 14.5-14.9 ms |
| `recordTrip` (not a client call) | 3.4 ms | 9 ms | 17.9 ms |
| ignition off + on (not a client call) | 8.5 ms | 12.7 ms | 122 ms |
| first `context(query)` after a restart | 6-10 ms once | | |

Figures move by a millisecond or so between runs. The run also shows one 85 ms `recordTrip`: the benchmark makes
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
