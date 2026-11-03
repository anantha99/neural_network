# carmem handoff

Written 2026-10-31 at the end of a cloud session. Everything below is on branch
`ccr-dca1f83b-ot2r6f` of `anantha99/neural_network`. The default branch still holds
only the two original notebooks; nothing from this work has been merged.

```
git clone https://github.com/anantha99/neural_network.git
cd neural_network && git checkout ccr-dca1f83b-ot2r6f
```

## What carmem is

On-device, identity-scoped memory for an in-car LLM assistant on Android Automotive OS,
usable by an on-device model (Gemma via LiteRT-LM) and a cloud agent through the same
interface. Five layers (identity, episodic, semantic, procedural, vehicle), a deterministic
consolidation state machine driven by an editable JSON policy, action tiers
(silent / suggest / act / never), one-word "no" corrections with demotion, tombstones so a
forgotten routine cannot be re-mined, and a context packet that masks sensitive fields for
the cloud. The design rationale and the technique audit against Mem0, Letta, Graphiti and
LangMem are in `docs/DESIGN.md`; read it first.

## State of each piece

| Piece | Path | Status |
|---|---|---|
| Python reference | `carmem/` | Complete for v1. 24 pytest tests pass. The oracle for every other implementation. |
| Benchmark | `bench/` | `python -m bench.run_bench` scores a synthetic household. All metrics at target. |
| Wire schema + fixtures | `carmem/wire.py`, `fixtures/` | Exported with `python -m bench.export_fixtures`. Re-export after any policy or consolidation change. |
| Kotlin core | `kotlin/carmem-core` | Line-for-line port. `ConformanceTest` replays the fixture and matches the reference exactly. 6 tests pass. |
| Car pipeline | `kotlin/carmem-car` | Trip segmenter, place resolver, session orchestrator. Pure JVM. 3 tests pass. |
| Android module | `kotlin/carmem-android` | Compiles and runs on the AAOS 15 emulator (2026-11-01). Ignition, HVAC, location and driver-user signals arrive; trips, an implicit climate fact and an act-tier proposal were verified end to end. Included in Gradle only when `ANDROID_HOME` or `kotlin/local.properties` points at an SDK. |
| Dev host | `kotlin/carmem-devhost` | Dev-only priv-app that hosts the service on the emulator, plus `sim/drive.sh` (three weeks of school runs). See its README. |
| LiteRT-LM adapter | `carmem-android/LiteRtLmClient.kt` | Written and compiled against litertlm-android 0.19.0 (`ResponseFormat.json` constrained decoding, greedy sampler, fresh conversation per call). Used when `gemma-3n-E2B-it-int4.litertlm` is in the service's `noBackupFilesDir`. Not yet run with a model. |
| Cloud model (dev) | `carmem-android/GeminiLlmClient.kt` | Gemma 4 (`gemma-4-26b-a4b-it`) on the Gemini API, schema enforced server-side, retries 429/5xx twice. Used when `gemini.key` (key, optional second line = model) is in `noBackupFilesDir` and no `.litertlm` model is. Verified on the emulator 2026-11-03. Sends raw utterances to Google: development only. `LiveLlmTest` runs the same path on the JVM when `GEMINI_API_KEY` is set. |
| Cloud bridge | not started | Koog-based client: send `context(query, "cloud").render()`, execute tool calls locally with `handleTool`, `unmask` the reply. |

## Build and test

```
pip install -e .[dev] && python -m pytest -q          # Python reference, 24 tests
python -m bench.run_bench                              # benchmark table
cd kotlin && gradle test                               # core + car on the JVM, 10 tests (+2 live, skipped without GEMINI_API_KEY)
cd kotlin && gradle :carmem-android:assembleDebug      # needs an Android SDK with platform 35
```

Gradle 8.13/8.14 and JDK 21. Kotlin 2.4.20 (raised from 2.1.20 because litertlm-android 0.19.0 is built
with Kotlin 2.4), AGP 8.9.1, kotlinx-serialization 1.8.0. There is no Gradle wrapper yet.
Maven Central rate-limited the cloud proxy; `--max-workers=1` and a retry fixed it.

## First things to do on a machine with Android Studio

Done on 2026-11-01 except where noted above; kept for reference.

1. Set `sdk.dir` in `kotlin/local.properties` and run `gradle :carmem-android:assembleDebug`.
   Expect compile errors; the module was written against API memory, not a compiler.
   Things most likely wrong, in order:
   - `androidx.sqlite:sqlite-bundled` version (`2.5.2` assumed) and the exact names of the
     `SQLITE_DATA_*` constants and `getColumnType` in `AndroidSqlDriver.kt`.
   - `kotlinOptions { jvmTarget }` may need the newer `kotlin { compilerOptions }` form.
   - `useLibrary("android.car")` requires `platforms/android-35/optional/android.car.jar`.
   - `CarOccupantZoneManager.getUserForOccupant` and `INVALID_USER_ID` names/visibility.
   - `BluetoothDevice.isConnected` is hidden API; the reflection fallback is deliberate.
2. Verify the three vehicle-property permission names in `AndroidManifest.xml` against the
   `VehiclePropertyIds` docs for the target AAOS release. They are privileged either way.
3. Run on the AAOS emulator with the car sensor controls: toggle ignition, set HVAC, move
   location, confirm `CarMemoryService` logs trips and that `ICarMemory.proactive` returns
   proposals from the right place after three weeks of simulated runs.

## Found on the emulator (2026-11-01)

- **Run the service in the driver's user, not user 0.** AAOS user 0 is headless: location listeners
  there stay inactive, and the database would be shared by everyone. The service is now a started
  foreground service of type `location`, which also avoids background location throttling.
- **Nobody is ever enrolled on device.** Unbound hard signals resolve to a guest, and guests never
  form memory. `ICarMemory.enroll` now exists. Open decision: whether the service should
  auto-enroll each non-guest AAOS user on first sight.
- **Routines key on place names, which change.** "home" is labelled only after the first night's
  dwell, so the first week's trips (`place-1 -> place-6`) most likely do not count toward the `home`
  routine: after three weeks it was a candidate with 2 occurrences. Probable fix: key on the stable
  place id and render the label.
- `isGuestUser(int)` is blocked hidden API; the guest check uses `createContextAsUser` +
  `isGuestUser()` (system APIs, via reflection) and needs `QUERY_USERS` and targetSdk >= 33.
- `android.useAndroidX=true` was missing from `gradle.properties`.

## Found running a real model (2026-11-03)

Gemma 4 through `GeminiLlmClient`, on the JVM and in the service on the emulator. Fixed in both the
Python reference and Kotlin:

- **The model invented keys** (`preferred_temperature`, `cabin_temperature`), so stated preferences did
  not line up with observed settings. The extraction prompt now names the canonical keys.
- **Act-tier proposals came from any climate/seat/media fact**, e.g. "Setting country music dislike to
  dislike." Only keys in `SETTING_CATEGORIES` (cabin_temp, fan, seat_position, media) become `set` actions.
- **Reconciliation saw values only** ("Wednesdays at 5" vs "Tuesdays at 5"), so the model added a second
  note instead of updating. It now sees `key: value` on both sides; the rules still compare values.

Still true: the Gemini free tier returns frequent 500s, and with retries a call can take 50–120 s.
`hear()` runs the model on the service's single worker thread, so car events queue behind it; a real
assistant should not wait on extraction. The emulator needs `svc wifi enable` and
`cmd wifi connect-network AndroidWifi open` after each boot, and with 2 GB of RAM car_service can
crash-loop at boot under host load (a cold restart fixed it).

## Decisions already made (do not re-litigate without reason)

- Kotlin port, not Rust. AAOS only for now.
- Reuse: LiteRT-LM for on-device Gemma (constrained decoding), AI Edge Gallery as the
  integration reference, AndroidX bundled SQLite behind `SqlDriver`, Koog for the cloud
  agent client. Mem0 / Letta / Graphiti / LangMem contribute techniques, not code.
- Memory tools are always executed on the car; the cloud agent gets a masked packet and
  client-executed tools. The memory never opens a network port.
- Driving-time intents (`no`, `yes`, `forget that`, `why`) are rule-based forever.
- Extraction runs off the critical path; the utterance is logged first.
- Gemma 3n E2B for extraction; Room is not used (the core speaks raw SQL via `SqlDriver`).
- Python stays the reference and research harness; tune the policy there, re-export fixtures.

## Known gaps and judgment calls

- HVAC area mask in `VehicleSignals.kt` assumes left-hand drive.
- Database relies on Android file-based encryption; SQLCipher would be a `SqlDriver` swap.
- Passenger-scoped writes are not implemented; seat occupancy is received but unused.
- `PlaceResolver` has no reverse geocoding; places are `home`, `work`, driver-named, or `place-N`.
- The hashed embedder is a stand-in; a LiteRT embedding model would slot behind `Embedder`.
- Tests seed clocks explicitly; the service uses `LocalDateTime.now()`.

## Suggested opening message for the next session

> Continue the carmem project on branch `ccr-dca1f83b-ot2r6f`. Read `docs/HANDOFF.md` and
> `docs/DESIGN.md` first. I have Android Studio and an SDK locally. Start by compiling
> `kotlin/carmem-android`, fix what the compiler reports, then run the service on the AAOS
> emulator and confirm trips and proposals. After that, implement the LiteRT-LM `LlmClient`.

## Changed in this pass (2026-11-03, Kotlin only)

- **Routines follow renamed places.** `PlaceResolver` reports every label change; `CarSession` calls
  `CarMemory.renamePlace(null, old, new, ts)`, which rewrites routines (destination, origin,
  signature, action, evidence), merges duplicates and re-evaluates them, moves route tombstones and
  rewrites trip episodes. The core stays keyed on names, so the fixture is unaffected. Naming a place
  with a label another place already has (moved house) first strips that label from the old place.
- **Auto-enrollment.** An unbound `seat_profile` (policy `auto_enroll_kinds`) enrolls
  `seat_profile:<value>` named `Driver <value>` instead of opening a guest session; unbound
  phones / fobs (policy `auto_bind_kinds`) seen with a recognised driver, and no other bound driver,
  are bound to them. Voice is never enrolled or bound. "my name is Asha" renames the driver
  (`CarMemory.rename`). `CarSession(onDriver = ...)` reports the resolved person after each ignition.
  Judgment call: a phone is bound on its first co-sighting; a passenger's phone reported with
  role `driver` would be bound too.
- **Clock.** `CarMemory.clock` is public; `DemoClock` pins "now" for demos. The service should pass a
  clock to `CarMemory` rather than `LocalDateTime.now()` at call sites.
- **Background extraction.** `hear(..., extract = false)` logs and handles intents at once;
  `CarSession(asyncExtraction = true, runOnMemoryThread = { handler.post(it) })` runs the model on an
  `ExtractionQueue` thread and applies on the memory thread. Not yet wired into `carmem-android`.

## Changed in this pass (Android, 2026-11-03)

Written without an Android SDK, so `carmem-android` and `carmem-devhost` have **not been compiled**.
`Assistant.kt` and `GeminiLlmClient.kt` were compiled on the JVM against the real core with a stub
`android.util.Log`, and a JVM scenario (seed, Mon 07:38, Asha in, no, why 21 degrees, forget the gym,
a cloud question through a fake `ChatModel`, a guest wiped at ignition off) behaved as the demo needs.

- **Service.** `CarMemory` runs on a `DemoClock` (field `clock`); every binder call and the platform adapters
  (`VehicleSignals`, `LocationFeed`, `PhoneSignal`, new `clock` constructor parameter) use it instead of
  `LocalDateTime.now()`. `CarSession(asyncExtraction = true, runOnMemoryThread = handler.post, onDriver = DriverBus)`;
  the extraction queue is closed in `onDestroy`. `hear` over AIDL no longer runs the model on the worker thread:
  it logs, handles intents and queues extraction.
- **AIDL additions:** `seed(fixtureJson)`, `setClock(isoOrEmpty)`, `injectEvent(eventJson)`, `chat(text, destination)`,
  `whoami()`. `seed` skips (returns `already_seeded`) when every fixture person exists, because a second replay
  double-counts evidence. `whoami` also returns `now` and `guest_scopes_remaining` (shown after ignition off).
- **Broadcasts:** `DriverBus` (`carmem.android.DRIVER`: `driver_id`, `name`, `is_guest`) and `MemoryBus`
  (`carmem.android.MEMORY_CHANGED`, after background extraction is applied) next to `ProposalBus`.
- **Assistant** (`carmem-android/Assistant.kt`): `ChatModel` / `GeminiChatModel` (Gemini function calling, tools from
  `MemoryTools.neutral` with `additionalProperties` stripped, thought parts skipped, model turns echoed verbatim so
  thought signatures go back). Rule intents answer offline; "why do you think I like 21 degrees?" explains the best
  keyword match for its subject (with small synonyms: degrees/temperature -> cabin temp), not only the last stated
  fact; "forget X" for something already tombstoned says so. Cloud: masked packet, the driver's text and tool
  results masked by substitution from `ContextPacket.masked`, tool args un-masked before they run, reply un-masked.
  `memory_remember` from the model logs and defers like `hear`. No key: answers from the packet. `geminiPost` is now
  a shared `internal` function in `GeminiLlmClient.kt`.
- **Dev host:** `DemoActivity` (launcher "carmem demo"), code-built three-pane screen with demo controls; probe ops
  `seed`, `clock`, `event`, `chat`, `whoami`; the fixture is packaged from `../../fixtures` as assets. Demo steps
  are in `kotlin/carmem-devhost/README.md`.

`// VERIFY:` left in the code:

- `Assistant.kt` (above `GeminiChatModel`): that `gemma-4-26b-a4b-it` accepts `tools` and returns `functionCall`
  parts on the Gemini API. If not, chat falls back to a packet answer with the error in `note`; put a Gemini model
  id on line 2 of `gemini.key`.
- `Assistant.kt` (`chat` body): lowercase JSON-schema `type` values in `functionDeclarations.parameters`; uppercase
  them if a 400 names the schema.
- `Assistant.kt` (`content`): function responses sent in a `"user"` turn (older docs used role `"function"`).
- `carmem-devhost/build.gradle.kts`: `assets.srcDir("../../fixtures")` resolves to the repo's `fixtures/`.
- `carmem-devhost/AndroidManifest.xml`: `distractionOptimized` meta-data on `DemoActivity`.
- `DemoActivity.kt` (`register`): `registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)` receives the same-app broadcasts.

Known limits and suggestions outside these modules:

- The fixture already forgets the gym (week 5), so the demo's beat 5 forgets the office routine instead ("forget that I go to the office"), which the fixture still has.
  For a live forget, the fixture would need the gym routine alive at the end (Python `bench/export_fixtures.py`).
- `CarSession` builds its `ExtractionQueue` without `onApplied`/`onError`, so the service learns about applied
  extractions only by wrapping `runOnMemoryThread` (hence `MemoryBus` carries no payload). An `onExtracted`
  parameter on `CarSession` would let the UI say what was stored.
- The rule extractor's `remember (that)? (.+)` stores questions such as "what should I remember about X?" as notes.
- Cloud masking only covers values of confirmed facts in masked categories; a sensitive phrase that appears in an
  episode in a different form than the fact's value is not caught.
- `FixtureReplay` drives `CarMemory` directly, so `seed` closes an open session through `CarSession` first; the
  service keeps its own policy (the fixture's equals the default apart from Kotlin-only keys).
- An unpinned clock plus the emulator's own `seat_profile user:10` signal (emitted at car connect) within 10 minutes
  of "Guest arrives" would auto-enroll "Driver 10" instead of a guest; pin the clock first.

## Run on the emulator (2026-11-04)

`gradle test :carmem-devhost:assembleDebug` compiled both Android modules with no changes needed; 20 JVM tests pass
(+2 live, skipped). All eight beats were run through the probes (`seed`, `clock`, `event`, `chat`, `whoami`,
`proactive`) against cloud Gemma 4 and gave the README's results: Asha's ACT climate + SUGGEST school route, "no"
skipped it, "why ... 21 degrees" explained from 10 observations, the office routine forgotten (nothing offered at
Oakwood Primary 08:05), the gym reported as already forgotten, the guest's "12 Elm Street" gone after ignition off
(0 guest scopes, no `home_address` fact), and the cloud question answered after a `memory_recall` tool round with
school and allergy masked as `<school_1>` / `<health_2>`.

VERIFY results:

- Gemma 4 (`gemma-4-26b-a4b-it`) accepts `tools` with lowercase schema types, returns `functionCall` parts (with
  an `id` and a `thoughtSignature`), and answers from a `functionResponse` sent in a `"user"` turn. Checked with
  direct requests and in the service. No Gemini model id is needed on line 2.
- The fixture is in the APK as `assets/household_seed7_w8.json`.
- Not verified: the `DemoActivity` screen and its receivers. On this emulator the display stopped updating (the
  launcher was black too, clock frozen) and the activity window never got a surface; a cold restart is the next step.

Fixed from the run:

- **Each chat utterance was stored and extracted twice**: `ask` logs it, then the model called `memory_remember`
  with the same words. `runTool` now returns the existing log for a `memory_remember` of the driver's own words.
- **`seed` called the cloud model for every fixture utterance** (slow, 500s, results differ from the fixture). It
  now replays through a model-less `CarMemory` on the same database, as the reference does. Seeding still takes
  ~80 s on this emulator (SQLite on 2 GB, not the model), not "a few seconds".
- At service start the emulator's `seat_profile user:10` auto-enrolls "Driver user:10" at wall time even with VHAL
  ignition off; `seed` closes that session, so the demo is unaffected, but the person remains.
