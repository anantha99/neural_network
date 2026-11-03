# carmem-android

Android Automotive OS module: the bound memory service plus adapters that turn
platform callbacks into `carmem.car.CarEvent`s. All logic lives in `carmem-car`
(pure JVM, tested); this module is deliberately thin.

| File | Role |
|---|---|
| `CarMemoryService` | foreground bound service, single worker thread, owns the database and a `DemoClock`; exposes `ICarMemory` over AIDL with JSON payloads in the wire schema; extraction runs on the session's `ExtractionQueue` |
| `ICarMemory.aidl` | hear / context / recall / proactive / handleTool / explain / forget / snapshot / unmask / enroll, plus the demo surface: seed / setClock / injectEvent / chat / whoami |
| `Assistant` | text-in / text-out assistant: rule intents (no / yes / forget / why) offline and at once, else `ChatModel` with the context packet and the six memory tools executed on the car; masks everything that goes to the cloud and un-masks the reply |
| `GeminiChatModel` | `ChatModel` over Gemini `generateContent` with function calling (Gemma 4 by default), sharing `geminiPost` (retries) with `GeminiLlmClient` |
| `AndroidSqlDriver` | `carmem.SqlDriver` over the AndroidX bundled SQLite driver |
| `VehicleSignals` | `IGNITION_STATE`, `HVAC_TEMPERATURE_SET`, `SEAT_OCCUPANCY` via `CarPropertyManager`; driver zone's user via `CarOccupantZoneManager` as a `seat_profile` signal |
| `LocationFeed` | `LocationManager` fixes for trip segmentation |
| `PhoneSignal` | connected bonded Bluetooth phone as a `phone` signal |
| `ProposalBus` | local broadcast of proactive proposals for the assistant UI / TTS (`carmem.android.PROPOSALS`, extra `proposals`) |
| `DriverBus` | local broadcast of the resolved driver after each ignition (`carmem.android.DRIVER`, extras `driver_id`, `name`, `is_guest`) |
| `MemoryBus` | local broadcast after background extraction is applied (`carmem.android.MEMORY_CHANGED`), so a UI can refresh |

## Build

Needs an Android SDK with platform 35 (the `optional/android.car.jar` is used via
`useLibrary("android.car")`). Set `ANDROID_HOME` or `kotlin/local.properties`
`sdk.dir=...`; `settings.gradle.kts` includes this module only when one is found.

```
cd kotlin && gradle :carmem-android:assembleDebug
```

## Deployment notes

- The vehicle-property permissions are signature|privileged. Ship on the OEM image
  (priv-app with a privapp-permissions allowlist) or have an OEM component forward the
  signals. The emulator's car sensor controls cover development.
- The database lives in `noBackupFilesDir`, which is credential-encrypted per Android user.
  For encryption at rest beyond file-based encryption, implement `SqlDriver` over SQLCipher.
- Clients bind with action `carmem.android.BIND`; the service is protected by a
  signature permission so only same-signer apps (the assistant, the cloud bridge) can bind.
- Voice identity, key fob and OEM-specific signals are posted as `OccupantObserved`
  events from whatever component has them; the identity policy decides what they mean.

## Clock, extraction and the assistant

- **Clock.** `CarMemory` gets the service's `DemoClock`; every binder call and every platform adapter
  (`VehicleSignals`, `LocationFeed`, `PhoneSignal`) stamps events with it. `setClock("2026-10-12T07:38:00")`
  pins it (time then runs forward from there), `setClock("")` returns to the wall clock.
- **Extraction off the worker thread.** `hear` and `chat` log the utterance and handle intents immediately;
  extraction (the model call) runs on `CarSession.extraction` and is applied back on the worker thread,
  after which `MemoryBus` fires. Queued extractions are dropped in `onDestroy`; their utterances stay logged.
- **Assistant.** `chat(text, "local" | "cloud")` runs `Assistant.ask` on the worker thread. The chat model is
  `GeminiChatModel` whenever `gemini.key` is in `noBackupFilesDir` (also for `"local"`: there is no on-device
  chat model yet, so "local" means "unmasked", not "offline"). Without a key, `chat` answers from the packet.
  The model's network calls block the worker thread for their duration, so car events queue behind a chat:
  acceptable for the demo, not for a product.
- **injectEvent** lets a demo drive the session without the VHAL: `{"type":"occupant","kind":"key_fob","value":"fob-asha"}`,
  `{"type":"location","lat":12.9716,"lon":77.5946}`, `{"type":"place","name":"home"}`, `{"type":"ignition","on":true}`.

