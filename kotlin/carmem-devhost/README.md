# carmem-devhost

Development-only app that hosts `CarMemoryService` on the AAOS emulator. `carmem-android`
is a library, so something has to install it, grant it the privileged vehicle permissions and
keep the service running. Not for shipping: the OEM image provides the real host.

| File | Role |
|---|---|
| `DevHostApp` | starts the service in the foreground and holds a bind for the probes |
| `ProbeReceiver` | adb entry point to `ICarMemory`; results go to logcat tag `carmem-probe` |
| `ProposalLogger` | logs what `ProposalBus` publishes, standing in for the assistant UI |
| `privapp-permissions-carmem.xml` | allowlist for the privileged permissions |
| `sim/drive.sh` | three weeks of Monday school runs, then checks the fourth Monday |

## Emulator setup (once)

System image `system-images;android-35-ext15;android-automotive;x86_64`: the Google APIs image,
not Play Store, because `adb root` and a writable system partition are needed.

```
emulator -avd <aaos avd> -writable-system -no-snapshot
adb root && adb remount && adb reboot        # the first remount disables verity
adb root && adb remount
```

## Install

```
cd kotlin && gradle :carmem-devhost:assembleDebug
adb shell mkdir -p /system/priv-app/CarMemDevHost
adb push carmem-devhost/build/outputs/apk/debug/carmem-devhost-debug.apk /system/priv-app/CarMemDevHost/CarMemDevHost.apk
adb push carmem-devhost/privapp-permissions-carmem.xml /system/etc/permissions/
adb reboot
adb shell pm grant --user 10 carmem.devhost android.permission.ACCESS_FINE_LOCATION
```

Privileged-app permissions are enforced on this image: a requested privileged permission
missing from the allowlist stops the system from booting.

## Run

```
adb shell am broadcast --user current -a carmem.debug.PROBE -n carmem.devhost/.ProbeReceiver --es op snapshot
adb logcat -s carmem:D carmem-probe:I
cd carmem-devhost/sim && ./drive.sh
```

`op` is one of `proactive`, `recall`, `hear`, `context`, `snapshot`, `explain` or `forget`, with
`--es arg <value>` for the argument.

## Why the service runs in the driver's user

AAOS runs user 0 as a headless system user. Location listeners there stay inactive because
user 0 is never the current user, and the database would be shared by everyone instead of
credential-encrypted per driver. So the host is not `persistent`. It starts the service as a
foreground service of type `location` in the current user, which also avoids background
location throttling.
