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
| Android module | `kotlin/carmem-android` | Written, never compiled: the cloud session could not download an Android SDK. Included in Gradle only when `ANDROID_HOME` or `kotlin/local.properties` points at an SDK. |
| LiteRT-LM adapter | not started | Implement `carmem.LlmClient.completeJson` over LiteRT-LM with JSON-schema constrained decoding. |
| Cloud bridge | not started | Koog-based client: send `context(query, "cloud").render()`, execute tool calls locally with `handleTool`, `unmask` the reply. |

## Build and test

```
pip install -e .[dev] && python -m pytest -q          # Python reference, 24 tests
python -m bench.run_bench                              # benchmark table
cd kotlin && gradle test                               # core + car on the JVM, 9 tests
cd kotlin && gradle :carmem-android:assembleDebug      # needs an Android SDK with platform 35
```

Gradle 8.14 and JDK 21 were used. Kotlin 2.1.20, AGP 8.9.1, kotlinx-serialization 1.8.0.
Maven Central rate-limited the cloud proxy; `--max-workers=1` and a retry fixed it.

## First things to do on a machine with Android Studio

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
