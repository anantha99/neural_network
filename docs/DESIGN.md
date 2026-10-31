# carmem design

## Why a car is a different memory problem

Four things change relative to a chat assistant, and each one shapes a layer below.

1. **Shared device.** A family, a guest, a valet. Identity is the key everything else is
   scoped by, and a record that surfaces for the wrong person is a bug.
2. **Sensor stream.** Trips, times, climate, who sat where. The useful memory is implicit,
   so the hard problem is *writing*, not retrieving.
3. **Driving workload.** The assistant cannot ask three clarifying questions. Recall has to
   be one-shot and proactive, and correction has to be one word.
4. **Offline.** The head unit owns the data. Cloud is a sync target and a place some
   requests get routed to, not the source of truth.

## Layers

```
identity ──► scope id ──┐
                        ▼
episodic (append-only) ──extraction / mining──► semantic ──┐
                        │                                  ├─► context packet ─► LLM
                        └──────────────────────► procedural┘
                                                            ▲
sensors ─────────────────────────────────────► vehicle ─────┘
```

* **Identity** (`identity.py`). Hard signals are seat profile, paired phone and key fob, in
  that priority. Voice is soft: it can choose between people the hard signals already put
  in the car (a borrowed fob plus a known phone), but on its own it never unlocks a profile
  because the cost of a false accept is another person's memory. No hard signal means a
  guest scope `guest:<id>`. Guests get memory for the trip (so "set it to 25" sticks) but
  the scope is purged at ignition off and guests never form routines.
* **Episodic** (`store.py`). Trips, utterances, sensor readings and system events, each with
  a hashed embedding for retrieval. Kept for `episodic_retention_days` (42). Every evidence
  entry in the layers above points back to an episode id so "why do you think that?" has an
  answer until the episode ages out.
* **Semantic** (`consolidation.py`: `state_fact`, `observe_setting`). Facts carry
  `category/key = value (when condition)`, a `source` of stated / implicit / sensor, a
  sensitivity, a support list and a contradiction list.
* **Procedural** (`observe_trip`, `evaluate_routine`). A routine is a destination at a
  weekday slot from a typical origin.
* **Vehicle** (`sensor_fact`, `_maybe_promote_vehicle`). Scope `vehicle`, visible to every
  occupant.

## The state machine

Every semantic and procedural record is `observed → candidate → confirmed → retired`.
Transitions are deterministic rules over the support and contradiction lists; every number
comes from `Policy`.

### Stated facts
"My kid's school is Oakwood" is confirmed immediately. Facts carry a validity interval
(`valid_from`, `valid_until`), as in Zep's Graphiti: a later contradictory statement never
deletes the old record, it closes its interval and attaches the new statement as the
contradiction, so "when did this change" has an answer. Free-text notes have no reliable
key, so they reconcile the way Mem0 does: the new note is compared with the most similar
existing notes and the outcome is add, update (invalidate and succeed) or no-op (add
evidence). Rules decide by default; an LLM may decide when one is configured.

### Implicit facts
An observed setting accumulates. Within `fact_window_days`, if there are at least
`fact_implicit_min_support` observations and the dominant value holds
`fact_confirm_ratio` of them, the fact is confirmed. If values disagree, the consolidator
tries to split on a condition (`season`, then `daypart`): the split is accepted only when
every group meets the support and ratio thresholds on its own and the groups disagree.
This is the deterministic form of "the model proposes a condition, the rule accepts it";
an LLM extractor can supply conditions the driver states, and the same rule gates them.

### Routines
Grouped by `(weekday, destination)` with a ±45-minute slot and a typical origin.

* candidate at 2 occurrences
* confirmed at 3 occurrences, 3 distinct weeks, ≥ 70 % of eligible weekday slots since
  first seen, and no contradiction in the last 14 days
* retired when not seen for 42 days

A trip from the same origin, in the same slot, to a different place is a contradiction,
unless the routine's own trip happened earlier that day (chained trips: school at 07:40
then office at 08:05 do not contradict each other). The driver *doing* the routine resets
the "no" streak and cancels same-day contradictions.

### Acting is separate from believing
A confirmed record is acted on only according to its **tier**, keyed by category in the
policy and lowered by demotion:

| Tier | Behaviour | Default categories |
|---|---|---|
| act | done without asking, trivially reversible | climate, seat, media |
| suggest | one line, silence means no | route |
| silent | stored, used only when asked | route prefs, home, school, notes, vehicle faults |
| never | confirmed but never proactive; demotion cannot change it | contacts, health |

Proposals are also gated by place: a route is suggested only when the car is at the
routine's usual origin and not already at its destination.

### Correction
"No" applies to the active proposal (the most recent suggest-tier one if there are
several, since acts are quiet). It adds a weighted contradiction, suppresses the record for
the rest of the session and, after `demote_after_consecutive_no` (2) consecutive
rejections, moves the record one tier down.

"Forget that" deletes the record **and writes a tombstone** keyed on the destination
(all weekdays) or on `category/key`, for `tombstone_days` (90). Without the tombstone the
miner re-derives the same routine from the same trips the following week, which is the
failure that makes drivers stop trusting the feature. An explicit re-statement by the
driver lifts a fact tombstone.

### Vehicle promotion
A sensor reading is vehicle memory at once. A person's claim about the car ("the left rear
sensor is flaky") stays in their scope until a second person says the same thing or a
sensor agrees (`vehicle_min_distinct_persons`).

## Read side: the context packet
`ContextBuilder.build(occupancy, query, destination)` assembles confirmed facts (stated
beats implicit for the same key; conditioned facts replace their base), confirmed and
candidate routines, vehicle facts, and the top episodes by a hybrid of keyword overlap and
hashed-embedding cosine, weighted by recency with a 14-day half-life. For `destination="cloud"` the policy's masked categories and
sensitivities are replaced by tokens like `<school_1>` in facts, routines and episode text;
the mapping stays on the device and `unmask()` restores the reply. This is the handshake
with a request router: the router decides *where* a request runs, the packet decides
*what travels with it*.

## Integration with any LLM
The core never imports a vendor SDK. `LLMClient` is one method,
`complete_json(system, user, schema) -> dict`. Adapters ship for the Anthropic SDK and for
any OpenAI-compatible endpoint (including local servers). If the model is unavailable or
returns junk, extraction falls back to the rule extractor, and driving-time intents are
always rule-based. Tool definitions are provided in neutral, Anthropic and OpenAI shapes.

## Benchmark
`bench/synth.py` builds a household with ground truth and traps; `bench/run_bench.py`
replays it day by day with the right identity signals, asks the car what it would do
before each trip from the trip's origin, applies the driver's scripted reactions, and
scores:

* promotion precision and recall of confirmed routines
* days to first correct proactive act per routine
* false proactive rate per tier
* correction half-life: suggestions after the first "no", and whether the record stayed
  confirmed
* resurrection after "forget": proposals and records after the forget week
* cross-person contamination and guest residue
* expected facts, including the seasonal climate split
* LongMemEval-style QA on the rendered context, with forbidden strings for leakage
* cloud masking

## Implementations
The Python package is the reference. `kotlin/carmem-core` is a line-for-line port for the
head unit, verified by replaying `fixtures/household_seed7_w8.json` with the protocol in
`fixtures/wire-schema.json` and matching the reference's outputs exactly.

## Head unit
`kotlin/carmem-car` turns the car's event stream into memory operations. Trips are
segmented from ignition and location: a trip ends at ignition off or after eight minutes
stationary with the engine running, which is what separates the school drop-off from the
drive to the office. Places are clusters with a 150 m radius; home is the cluster with the
most night dwell, work the one with the most weekday daytime dwell, and the driver can name
any place. Identity signals are buffered for ten minutes and resolved at ignition on; a
hard driver signal arriving mid-session re-resolves the occupant. Proposals are computed
once per leg from the current place. `kotlin/carmem-android` hosts this in a bound service
with one worker thread (one writer, no locks in the core) and exposes the wire schema over
AIDL so the on-device assistant and the cloud bridge see identical shapes.

## Not yet built
* Device verification of `carmem-android` (it compiles only with an Android SDK present),
  keystore-backed or SQLCipher encryption beyond file-based encryption, right-hand-drive
  HVAC area mapping, and passenger-scoped writes from seat occupancy.
* The LiteRT-LM `LlmClient` with JSON-schema constrained decoding.
* The cloud bridge module (Koog-based agent client with client-executed memory tools).
* An actual on-device model for extraction and condition proposal; today the LLM path is an
  adapter, the deterministic path is the default.
* Passenger-scoped writes (a passenger's utterance is attributed to the passenger, but
  trips are only evidence for the driver).
* Sync protocol beyond `snapshot()`; conflict resolution between two cars sharing a person.
* sqlite-vec or a real embedding model for larger stores; the hashed embedder is fine for
  the thousands of episodes a car keeps.
