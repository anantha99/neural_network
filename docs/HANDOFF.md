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

## Head-unit readiness pass (2026-11-12)

Kotlin only; the Python reference was left as it is, and the conformance test still passes.

- Latency: every client call is under 5 ms at p99 on a build machine against a six-month database (was up to
  25 ms). Table and causes in `kotlin/README.md`; run `./gradlew :carmem-core:latency`. Not yet measured on a
  head unit: use `ICarMemory.stats()` there.
- Fixed from the lists below: nobody enrolled (the profile is enrolled on first sight), routines keyed on place
  names that change (`renamePlace`), no motion input (seat changes withheld while moving).
- New findings, fixed: every location fix on the road created a place, so the place list grew with every
  kilometre; places were saved only in `onDestroy`; proposals were broadcast to the host's own package only;
  `memory_remember` dropped text no rule matched.
- **Compiled, not run:** `carmem-core` and `carmem-car` are built and tested (22 tests). The edits in
  `carmem-android` and `carmem-devhost` (`CarMemoryService`, `AndroidSqlDriver`, `ICarMemory.aidl`, `ProbeReceiver`)
  compile in CI, whose runners have an Android SDK (debug and release, PR #4), but have not been run on an
  emulator or a head unit since. Run them there before anything else.
- Still open: `hear` blocks the worker when an `LlmClient` is set; a failed platform guest check treats the user
  as a known profile, which now also enrols them.

## Build and test

```
pip install -e .[dev] && python -m pytest -q          # Python reference, 24 tests
python -m bench.run_bench                              # benchmark table
cd kotlin && ./gradlew test                               # core + car on the JVM, 22 tests (+2 live, skipped without GEMINI_API_KEY)
cd kotlin && ./gradlew :carmem-android:assembleDebug      # needs an Android SDK with platform 35
```

Gradle 8.13/8.14 and JDK 21. Kotlin 2.4.20 (raised from 2.1.20 because litertlm-android 0.19.0 is built
with Kotlin 2.4), AGP 8.9.1, kotlinx-serialization 1.8.0. The Gradle wrapper pins 8.14.3.
Maven Central rate-limited the cloud proxy; `--max-workers=1` and a retry fixed it.

## First things to do on a machine with Android Studio

Done on 2026-11-01 except where noted above; kept for reference.

1. Set `sdk.dir` in `kotlin/local.properties` and run `./gradlew :carmem-android:assembleDebug`.
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
