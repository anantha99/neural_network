# carmem-devhost

Development-only app that hosts `CarMemoryService` on the AAOS emulator. `carmem-android`
is a library, so something has to install it, grant it the privileged vehicle permissions and
keep the service running. Not for shipping: the OEM image provides the real host.

| File | Role |
|---|---|
| `DevHostApp` | starts the service in the foreground and holds a bind for the probes and the demo screen |
| `DemoActivity` | the demo screen: transcript, proposals, what the car knows, demo controls (launcher entry "carmem demo") |
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

`op` is one of `proactive`, `recall`, `hear`, `context`, `snapshot`, `explain`, `forget`, `enroll`,
`seed`, `clock`, `event`, `chat` or `whoami`, with `--es arg <value>` for the argument:

```
probe() { adb shell am broadcast --user current -a carmem.debug.PROBE -n carmem.devhost/.ProbeReceiver --es op "$1" ${2:+--es arg "'$2'"} "${@:3}"; }
probe seed                                   # replays assets/household_seed7_w8.json
probe clock 2026-10-12T07:38:00              # or: probe clock reset
probe event '{"type":"occupant","kind":"key_fob","value":"fob-asha"}'
probe chat 'why do you think I like 21 degrees?'
probe chat 'Anything I should keep in mind for the school run today?' --es dest cloud
probe whoami
```

## Demo

The seven-beat demo runs from `DemoActivity` (app grid: "carmem demo"). The fixture
`fixtures/household_seed7_w8.json` is packaged as an asset straight from the repo's `fixtures/`
directory (`sourceSets.main.assets.srcDir("../../fixtures")`), so there is no copy to keep in sync.

**Install** as above, then, in the driver's user (10 on the stock image):

```
# cloud Gemma for extraction and chat (dev only; sends text to Google). Line 2 (model) is optional.
printf '%s\n' "$GEMINI_API_KEY" > gemini.key
adb push gemini.key /data/user/10/carmem.devhost/no_backup/gemini.key
# if logcat then shows "chat=none", the app cannot read the pushed file: give it to the app's uid
#   adb shell 'chown $(stat -c %u:%g /data/user/10/carmem.devhost) /data/user/10/carmem.devhost/no_backup/gemini.key; restorecon /data/user/10/carmem.devhost/no_backup/gemini.key'
adb shell svc wifi enable && adb shell cmd wifi connect-network AndroidWifi open
# park the emulator's own GPS at the demo home so the real location feed agrees with the injected fixes
adb emu geo fix 77.5946 12.9716
adb shell am force-stop --user 10 carmem.devhost
adb shell am start --user 10 -n carmem.devhost/.DemoActivity
```

**Run**, top bar left to right, then the transcript input (the phrase buttons under it fill the input):

1. **Seed**: eight weeks of Asha and Ben in a few seconds (skipped if already seeded).
2. **Clock → Mon 07:38** (2026-10-12, the Monday after the fixture ends). Set the clock *before* anyone
   gets in: events are stamped with the demo clock, and a session opened at wall time and continued at
   demo time would see time run backwards.
3. **Asha arrives**: fob + phone, a fix at home (named "home"), ignition on. The transcript greets Asha;
   the proposals pane shows the climate change (ACT, done unasked) and the school route (SUGGEST).
   **Propose now** asks again for the place in the field (default `home`).
4. **No** (or say "no"): the route suggestion is skipped for this trip and the cards clear.
5. Say "my daughter is allergic to penicillin": logged at once, extracted by cloud Gemma in the background
   (the right pane refreshes when it lands, typically 5-60 s). Say "why do you think I like 21 degrees?":
   answered offline from the evidence.
6. Say "forget that I go to the office". The Riverside Office routine is deleted and tombstoned: the right
   pane no longer lists it, and **Propose now** with location `Oakwood Primary` at 08:05 offers nothing.
   (The fixture already forgot the gym in week 5, so "forget the gym" would only report it as already gone;
   **Clock → Fri 18:28** and **Propose now** shows that one stayed forgotten.)
7. **Guest arrives** (voice only, 0.6), say "my home is 12 Elm Street", **Ignition off**: the transcript
   reports 0 guest records left on the device, and the right pane shows nobody.
8. **Asha arrives** again, then **Ask cloud** with "Anything I should keep in mind for the school run today?":
   the transcript shows the masked packet that went up, each tool call the model made with the (masked)
   result the car sent back, the model's raw reply and the un-masked reply. Toggle **Cloud** on the right
   pane to see the same masking there.

**Start over**: delete the database (keeps `gemini.key`) and relaunch.

```
adb shell am force-stop --user 10 carmem.devhost
adb shell rm -f '/data/user/10/carmem.devhost/no_backup/carmem.db*' /data/user/10/carmem.devhost/no_backup/places.tsv
```

## Why the service runs in the driver's user

AAOS runs user 0 as a headless system user. Location listeners there stay inactive because
user 0 is never the current user, and the database would be shared by everyone instead of
credential-encrypted per driver. So the host is not `persistent`. It starts the service as a
foreground service of type `location` in the current user, which also avoids background
location throttling.
