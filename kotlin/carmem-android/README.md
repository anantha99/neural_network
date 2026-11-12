# carmem-android

Android Automotive OS module: the bound memory service plus adapters that turn
platform callbacks into `carmem.car.CarEvent`s. All logic lives in `carmem-car`
(pure JVM, tested); this module is deliberately thin.

| File | Role |
|---|---|
| `CarMemoryService` | foreground bound service, single worker thread, owns the database; exposes `ICarMemory` over AIDL with JSON payloads in the wire schema |
| `ICarMemory.aidl` | hear / context / recall / proactive / handleTool / explain / forget / snapshot / unmask |
| `AndroidSqlDriver` | `carmem.SqlDriver` over the AndroidX bundled SQLite driver |
| `VehicleSignals` | `IGNITION_STATE`, `HVAC_TEMPERATURE_SET`, `HVAC_FAN_SPEED`, `SEAT_OCCUPANCY` via `CarPropertyManager`; driver zone's user via `CarOccupantZoneManager` as a `seat_profile` signal |
| `MediaSignal` | the genre or app that is playing, via `MediaSessionManager`, as a `media` setting; needs the privileged `MEDIA_CONTENT_CONTROL` |
| `LocationFeed` | `LocationManager` fixes for trip segmentation |
| `PhoneSignal` | connected bonded Bluetooth phone as a `phone` signal |
| `ProposalBus` | broadcast of proactive proposals for the assistant UI / TTS: to the host app, and to any app holding `carmem.permission.BIND_MEMORY` |

## Using it from your own assistant

The assistant keeps its own model; carmem is the memory behind it.

1. Sign the assistant with the same key as the app hosting the service, declare
   `<uses-permission android:name="carmem.permission.BIND_MEMORY" />`, and bind with action `carmem.android.BIND`.
2. Do not provision a model for carmem (no `.litertlm` file, no `gemini.key` in the service's `noBackupFilesDir`).
   Extraction then stays rule-based and `hear` returns in about a millisecond instead of waiting for a model.
3. Per turn: `context(query, "local")` for the system prompt, `hear(text, null)` with what the driver said (it
   handles "no", "yes", "forget that", "why" and "save this place as ..." on its own), `said(reply)` with what the
   assistant answered, and `handleTool` for the six memory tools. When your model decides something is worth
   keeping, call `memory_remember`: a known pattern becomes a typed fact, anything else a note.
   - "Take me there", "call him": when the assistant resolves something the memory cannot know (a search result,
     a contact from the phone book), tell it with `mention("place", "Luigi's")`. The context then carries an
     "In focus" line, and `resolve(text)` answers directly. This lasts five minutes or the drive and is never stored.
   - Starting navigation: `setDestination(name, kind, lat, lon)`. On arrival the place takes that name and kind,
     which is what later answers "the restaurant from last Friday".
   - A passenger speaking: pass any stable id for the voice or seat as `speakerId`, and use `contextFor` for their
     turn. `state()` says how many seats are occupied.
4. Proposals: register a receiver for `carmem.android.PROPOSALS` at runtime, or call `proactive(null)`, which
   answers for where the car is now. Seat changes are withheld while the car is moving.
5. The driver's Android user profile is enrolled on first sight as `user:<id>` with the name `Driver <id>`. Call
   `enroll("user:<id>", "<name>", "[]")` to give it a real name. Key fobs and phones still need `enroll` bindings.
6. Identity from elsewhere in the car (key fob, face, voice id): `observeOccupant(kind, value, confidence, role)`
   shortly before or after ignition on, with the value bound to a person through `enroll`.
7. The driver's rights: `exportMemory(null)` returns everything held about the current driver; `forget(target)`
   removes one memory and its trail; `erase(personId)` removes a person; `erase(null)` wipes the memory for resale.
   For a rental or fleet car put `{"guest_only": true}` in `policy.json` in the service's `noBackupFilesDir`.
8. `stats()` returns per-call latency measured inside the service. Run it after a drive on the target hardware:
   the 50 ms budget is checked there, not on a build machine.

## Build

Needs an Android SDK with platform 35 (the `optional/android.car.jar` is used via
`useLibrary("android.car")`). Set `ANDROID_HOME` or `kotlin/local.properties`
`sdk.dir=...`; `settings.gradle.kts` includes this module only when one is found.

```
cd kotlin && ./gradlew :carmem-android:assembleDebug
```

## Deployment notes

- The vehicle-property permissions are signature|privileged. Ship on the OEM image
  (priv-app with a privapp-permissions allowlist) or have an OEM component forward the
  signals. The emulator's car sensor controls cover development.
- The database runs with a write-ahead log and `synchronous=NORMAL`: a power cut cannot corrupt it but can lose
  the last few writes. The log is folded into the database at each ignition off.
- The database lives in `noBackupFilesDir`, which is credential-encrypted per Android user.
  For encryption at rest beyond file-based encryption, implement `SqlDriver` over SQLCipher.
- Clients bind with action `carmem.android.BIND`; the service is protected by a
  signature permission so only same-signer apps (the assistant, the cloud bridge) can bind.
- Voice identity, key fob and OEM-specific signals are posted as `OccupantObserved`
  events from whatever component has them; the identity policy decides what they mean.
