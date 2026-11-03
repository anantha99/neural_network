# carmem-android

Android Automotive OS module: the bound memory service plus adapters that turn
platform callbacks into `carmem.car.CarEvent`s. All logic lives in `carmem-car`
(pure JVM, tested); this module is deliberately thin.

| File | Role |
|---|---|
| `CarMemoryService` | foreground bound service, single worker thread, owns the database; exposes `ICarMemory` over AIDL with JSON payloads in the wire schema |
| `ICarMemory.aidl` | hear / context / recall / proactive / handleTool / explain / forget / snapshot / unmask |
| `AndroidSqlDriver` | `carmem.SqlDriver` over the AndroidX bundled SQLite driver |
| `VehicleSignals` | `IGNITION_STATE`, `HVAC_TEMPERATURE_SET`, `SEAT_OCCUPANCY` via `CarPropertyManager`; driver zone's user via `CarOccupantZoneManager` as a `seat_profile` signal |
| `LocationFeed` | `LocationManager` fixes for trip segmentation |
| `PhoneSignal` | connected bonded Bluetooth phone as a `phone` signal |
| `ProposalBus` | local broadcast of proactive proposals for the assistant UI / TTS |

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
